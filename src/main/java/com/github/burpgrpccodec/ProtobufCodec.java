package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

final class ProtobufCodec {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_RECURSION = 24;

    boolean looksLikeProtobuf(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        try {
            decodeMessage(bytes, 0);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    ObjectNode decodeMessage(byte[] bytes) {
        return decodeMessage(bytes, 0);
    }

    byte[] encodeMessage(ObjectNode object) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            int fieldNumber = parseFieldName(field.getKey());
            JsonNode value = field.getValue();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    writeField(out, fieldNumber, item);
                }
            } else {
                writeField(out, fieldNumber, value);
            }
        }
        return out.toByteArray();
    }

    private ObjectNode decodeMessage(byte[] bytes, int depth) {
        if (depth > MAX_RECURSION) {
            throw new IllegalArgumentException("protobuf nesting is too deep");
        }
        ProtoReader reader = new ProtoReader(bytes);
        ObjectNode object = NODES.objectNode();
        while (!reader.exhausted()) {
            long key = reader.readVarint();
            int fieldNumber = (int) (key >>> 3);
            int wireType = (int) (key & 0x07);
            if (fieldNumber <= 0) {
                throw new IllegalArgumentException("invalid protobuf field number");
            }
            ObjectNode field = switch (wireType) {
                case 0 -> typed("varint").put("value", reader.readVarint());
                case 1 -> typed("fixed64").put("value", reader.readFixed64());
                case 2 -> decodeLengthDelimited(reader.readBytes(), depth + 1);
                case 5 -> typed("fixed32").put("value", reader.readFixed32());
                default -> throw new IllegalArgumentException("unsupported protobuf wire type " + wireType);
            };
            appendField(object, "f" + fieldNumber, field);
        }
        return object;
    }

    private ObjectNode decodeLengthDelimited(byte[] value, int depth) {
        String text = decodeUtf8(value);
        if (text != null && isReadableText(text)) {
            return typed("string").put("value", text);
        }
        if (value.length > 0) {
            try {
                ObjectNode nested = decodeMessage(value, depth);
                ObjectNode node = typed("message");
                node.set("value", nested);
                return node;
            } catch (RuntimeException ignored) {
                // Fall through to bytes when nested protobuf parsing is not coherent.
            }
        }
        return typed("bytes").put("value", Base64.getEncoder().encodeToString(value));
    }

    private void writeField(ByteArrayOutputStream out, int fieldNumber, JsonNode field) {
        String type = field.path("type").asText();
        JsonNode value = field.path("value");
        switch (type) {
            case "varint" -> {
                writeVarint(out, ((long) fieldNumber << 3));
                writeVarint(out, value.asLong());
            }
            case "fixed64" -> {
                writeVarint(out, ((long) fieldNumber << 3) | 1);
                writeLittleEndian64(out, value.asLong());
            }
            case "string" -> writeLengthDelimited(out, fieldNumber, value.asText().getBytes(StandardCharsets.UTF_8));
            case "bytes" -> writeLengthDelimited(out, fieldNumber, Base64.getDecoder().decode(value.asText()));
            case "message" -> writeLengthDelimited(out, fieldNumber, encodeMessage((ObjectNode) value));
            case "fixed32" -> {
                writeVarint(out, ((long) fieldNumber << 3) | 5);
                writeLittleEndian32(out, value.asLong());
            }
            default -> throw new IllegalArgumentException("unsupported field type: " + type);
        }
    }

    private static ObjectNode typed(String type) {
        ObjectNode node = NODES.objectNode();
        node.put("type", type);
        return node;
    }

    private static void appendField(ObjectNode object, String name, ObjectNode value) {
        JsonNode existing = object.get(name);
        if (existing == null) {
            object.set(name, value);
            return;
        }
        if (existing.isArray()) {
            ((ArrayNode) existing).add(value);
            return;
        }
        ArrayNode values = NODES.arrayNode();
        values.add(existing);
        values.add(value);
        object.set(name, values);
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static boolean isReadableText(String text) {
        if (text.isEmpty()) {
            return true;
        }
        int readable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c != 0x7f)) {
                readable++;
            }
        }
        return readable == text.length();
    }

    private static int parseFieldName(String name) {
        if (!name.matches("f[1-9][0-9]*")) {
            throw new IllegalArgumentException("field names must use f<number>: " + name);
        }
        return Integer.parseInt(name.substring(1));
    }

    private static void writeLengthDelimited(ByteArrayOutputStream out, int fieldNumber, byte[] bytes) {
        writeVarint(out, ((long) fieldNumber << 3) | 2);
        writeVarint(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeLittleEndian32(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array());
    }

    private static void writeLittleEndian64(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }
}
