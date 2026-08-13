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
import java.util.Optional;

final class ProtobufCodec {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_RECURSION = 24;
    private final SchemaRegistry schemas;

    ProtobufCodec() {
        this(new SchemaRegistry());
    }

    ProtobufCodec(SchemaRegistry schemas) {
        this.schemas = schemas;
    }

    boolean looksLikeProtobuf(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        try {
            decodeMessage(bytes);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    ObjectNode decodeMessage(byte[] bytes) {
        return decodeMessage(bytes, Optional.empty(), 0);
    }

    ObjectNode decodeMessage(byte[] bytes, SchemaMessage schema) {
        return decodeMessage(bytes, Optional.ofNullable(schema), 0);
    }

    byte[] encodeMessage(ObjectNode object) {
        return encodeMessage(object, null);
    }

    byte[] encodeMessage(ObjectNode object, SchemaMessage schema) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            int fieldNumber = parseFieldName(field.getKey());
            SchemaField schemaField = schema == null ? null : schema.field(fieldNumber);
            JsonNode value = field.getValue();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    writeField(out, fieldNumber, item, schemaField);
                }
            } else {
                writeField(out, fieldNumber, value, schemaField);
            }
        }
        return out.toByteArray();
    }

    private ObjectNode decodeMessage(byte[] bytes, Optional<SchemaMessage> schema, int depth) {
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
            SchemaField schemaField = schema.map(message -> message.field(fieldNumber)).orElse(null);
            ObjectNode field = switch (wireType) {
                case 0 -> decodeVarint(reader.readVarint(), schemaField);
                case 1 -> decodeFixed64(reader.readFixed64(), schemaField);
                case 2 -> decodeLengthDelimited(reader.readBytes(), schemaField, depth + 1);
                case 5 -> decodeFixed32(reader.readFixed32(), schemaField);
                default -> throw new IllegalArgumentException("unsupported protobuf wire type " + wireType);
            };
            applyMetadata(field, schemaField);
            appendField(object, "f" + fieldNumber, field);
        }
        return object;
    }

    private ObjectNode decodeVarint(long value, SchemaField schemaField) {
        if (schemaField == null) {
            return typed("varint").put("value", value);
        }
        return switch (schemaField.protoType()) {
            case "bool" -> typed("bool").put("value", value != 0);
            case "sint32", "sint64" -> typed(schemaField.protoType()).put("value", decodeZigZag(value));
            case "int32", "uint32", "int64", "uint64", "enum" -> typed(schemaField.protoType()).put("value", value);
            default -> typed("varint").put("value", value);
        };
    }

    private ObjectNode decodeFixed64(long value, SchemaField schemaField) {
        if (schemaField == null) {
            return typed("fixed64").put("value", value);
        }
        return switch (schemaField.protoType()) {
            case "double" -> typed("double").put("value", Double.longBitsToDouble(value));
            case "fixed64", "sfixed64" -> typed(schemaField.protoType()).put("value", value);
            default -> typed("fixed64").put("value", value);
        };
    }

    private ObjectNode decodeFixed32(long value, SchemaField schemaField) {
        if (schemaField == null) {
            return typed("fixed32").put("value", value);
        }
        return switch (schemaField.protoType()) {
            case "float" -> typed("float").put("value", Float.intBitsToFloat((int) value));
            case "fixed32", "sfixed32" -> typed(schemaField.protoType()).put("value", value);
            default -> typed("fixed32").put("value", value);
        };
    }

    private ObjectNode decodeLengthDelimited(byte[] value, SchemaField schemaField, int depth) {
        if (schemaField != null) {
            switch (schemaField.protoType()) {
                case "string" -> {
                    return typed("string").put("value", new String(value, StandardCharsets.UTF_8));
                }
                case "bytes" -> {
                    return typed("bytes").put("value", Base64.getEncoder().encodeToString(value));
                }
                default -> {
                    if (!schemaField.messageType().isBlank()) {
                        ObjectNode node = typed("message");
                        node.put("messageType", schemaField.messageType());
                        node.set("value", decodeMessage(value, schemas.message(schemaField.messageType()), depth));
                        return node;
                    }
                }
            }
        }
        String text = decodeUtf8(value);
        if (text != null && isReadableText(text)) {
            return typed("string").put("value", text);
        }
        if (value.length > 0) {
            try {
                ObjectNode nested = decodeMessage(value, Optional.empty(), depth);
                ObjectNode node = typed("message");
                node.set("value", nested);
                return node;
            } catch (RuntimeException ignored) {
                // Fall through to bytes when nested protobuf parsing is not coherent.
            }
        }
        return typed("bytes").put("value", Base64.getEncoder().encodeToString(value));
    }

    private void writeField(ByteArrayOutputStream out, int fieldNumber, JsonNode field, SchemaField schemaField) {
        String type = field.path("type").asText(defaultType(schemaField));
        JsonNode value = field.path("value");
        switch (type) {
            case "varint", "int32", "int64", "uint32", "uint64", "enum" -> {
                writeVarint(out, ((long) fieldNumber << 3));
                writeVarint(out, value.asLong());
            }
            case "bool" -> {
                writeVarint(out, ((long) fieldNumber << 3));
                writeVarint(out, value.asBoolean() ? 1 : 0);
            }
            case "sint32", "sint64" -> {
                writeVarint(out, ((long) fieldNumber << 3));
                writeVarint(out, encodeZigZag(value.asLong()));
            }
            case "fixed64", "sfixed64" -> {
                writeVarint(out, ((long) fieldNumber << 3) | 1);
                writeLittleEndian64(out, value.asLong());
            }
            case "double" -> {
                writeVarint(out, ((long) fieldNumber << 3) | 1);
                writeLittleEndian64(out, Double.doubleToRawLongBits(value.asDouble()));
            }
            case "string" -> writeLengthDelimited(out, fieldNumber, value.asText().getBytes(StandardCharsets.UTF_8));
            case "bytes" -> writeLengthDelimited(out, fieldNumber, Base64.getDecoder().decode(value.asText()));
            case "message" -> writeLengthDelimited(out, fieldNumber, encodeMessage((ObjectNode) value));
            case "fixed32", "sfixed32" -> {
                writeVarint(out, ((long) fieldNumber << 3) | 5);
                writeLittleEndian32(out, value.asLong());
            }
            case "float" -> {
                writeVarint(out, ((long) fieldNumber << 3) | 5);
                writeLittleEndian32(out, Float.floatToRawIntBits((float) value.asDouble()));
            }
            default -> throw new IllegalArgumentException("unsupported field type: " + type);
        }
    }

    private static ObjectNode typed(String type) {
        ObjectNode node = NODES.objectNode();
        node.put("type", type);
        return node;
    }

    private static void applyMetadata(ObjectNode field, SchemaField schemaField) {
        if (schemaField == null) {
            return;
        }
        field.put("name", schemaField.name());
        field.put("protoType", schemaField.protoType());
        field.put("repeated", schemaField.repeated());
        if (!schemaField.messageType().isBlank()) {
            field.put("messageType", schemaField.messageType());
        }
        if (!schemaField.enumType().isBlank()) {
            field.put("enumType", schemaField.enumType());
        }
    }

    private static String defaultType(SchemaField schemaField) {
        return schemaField == null ? "" : schemaField.protoType();
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

    private static long decodeZigZag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static long encodeZigZag(long value) {
        return (value << 1) ^ (value >> 63);
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
