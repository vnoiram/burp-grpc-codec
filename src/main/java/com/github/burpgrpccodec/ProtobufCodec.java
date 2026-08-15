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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

final class ProtobufCodec {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern FIELD_NAME = Pattern.compile("f[1-9][0-9]*");
    // Three base64url segments of at least 10 chars each; the length floor keeps
    // ordinary dotted strings (hostnames, paths) from being flagged as JWTs.
    private static final Pattern JWT_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
    private static final Pattern SENSITIVE_FIELD_NAME = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|apikey|authorization|"
                    + "session[_-]?id|private[_-]?key|access[_-]?token|refresh[_-]?token)");
    private static final SchemaMessage ANY_ENVELOPE_SCHEMA = new SchemaMessage(
            "google.protobuf.Any",
            Map.of(
                    1, new SchemaField(1, "type_url", "string", "", "", false, false),
                    2, new SchemaField(2, "value", "bytes", "", "", false, false)),
            Map.of());
    private static final Set<String> WRAPPER_TYPES = Set.of(
            "google.protobuf.DoubleValue", "google.protobuf.FloatValue",
            "google.protobuf.Int64Value", "google.protobuf.UInt64Value",
            "google.protobuf.Int32Value", "google.protobuf.UInt32Value",
            "google.protobuf.BoolValue", "google.protobuf.StringValue",
            "google.protobuf.BytesValue");
    private final SchemaRegistry schemas;
    private volatile int maxRecursion;

    ProtobufCodec() {
        this(new SchemaRegistry());
    }

    ProtobufCodec(SchemaRegistry schemas) {
        this(schemas, 24);
    }

    ProtobufCodec(SchemaRegistry schemas, int maxRecursion) {
        this.schemas = schemas;
        this.maxRecursion = maxRecursion > 0 ? maxRecursion : 24;
    }

    void setMaxRecursion(int maxRecursion) {
        this.maxRecursion = maxRecursion > 0 ? maxRecursion : 24;
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
            int fieldNumber = resolveFieldNumber(schema, field.getKey());
            SchemaField schemaField = schema == null ? null : schema.field(fieldNumber);
            JsonNode value = field.getValue();
            if (schemaField != null && schemaField.packed() && value.isArray()) {
                writePackedField(out, fieldNumber, value, schemaField);
                continue;
            }
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
        if (depth > maxRecursion) {
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
            JsonNode field = switch (wireType) {
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
        ObjectNode node = switch (schemaField.protoType()) {
            case "bool" -> typed("bool").put("value", value != 0);
            case "sint32", "sint64" -> typed(schemaField.protoType()).put("value", decodeZigZag(value));
            case "int32", "uint32", "int64", "uint64", "enum" -> typed(schemaField.protoType()).put("value", value);
            default -> typed("varint").put("value", value);
        };
        if (!schemaField.enumType().isBlank()) {
            schemas.enumName(schemaField.enumType(), value).ifPresent(name -> node.put("enumName", name));
        }
        return node;
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

    private JsonNode decodeLengthDelimited(byte[] value, SchemaField schemaField, int depth) {
        if (schemaField != null) {
            switch (schemaField.protoType()) {
                case "string" -> {
                    return stringNode(new String(value, StandardCharsets.UTF_8));
                }
                case "bytes" -> {
                    return bytesNode(value);
                }
                default -> {
                    if (schemaField.repeated() && schemaField.packed()) {
                        return decodePacked(value, schemaField);
                    }
                    if ("google.protobuf.Any".equals(schemaField.messageType())) {
                        return decodeAny(value, depth);
                    }
                    if (!schemaField.messageType().isBlank()) {
                        ObjectNode node = typed("message");
                        node.put("messageType", schemaField.messageType());
                        ObjectNode nested = decodeMessage(value, schemas.message(schemaField.messageType()), depth);
                        node.set("value", nested);
                        addReadableWellKnownView(node, schemaField.messageType(), nested);
                        return node;
                    }
                }
            }
        }
        String text = decodeUtf8(value);
        if (text != null && isReadableText(text)) {
            return stringNode(text);
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
        return bytesNode(value);
    }

    private static ObjectNode stringNode(String text) {
        ObjectNode node = typed("string").put("value", text);
        valueHint(text).ifPresent(hint -> node.put("hint", hint));
        return node;
    }

    private static ObjectNode bytesNode(byte[] value) {
        return typed("bytes")
                .put("value", Base64.getEncoder().encodeToString(value))
                .put("hex", HEX.formatHex(value));
    }

    /**
     * Best-effort, low-precision spotting of values worth a closer look
     * during recon (currently just JWTs), not a security scanner. Segment
     * length thresholds are chosen to avoid flagging ordinary dotted
     * strings (hostnames, paths) as false positives.
     */
    private static Optional<String> valueHint(String text) {
        if (JWT_PATTERN.matcher(text).matches()) {
            return Optional.of("jwt");
        }
        return Optional.empty();
    }

    /**
     * Best-effort flagging of field names that conventionally hold
     * credentials, independent of the decoded value shape.
     */
    private static Optional<String> nameHint(String name) {
        return name != null && SENSITIVE_FIELD_NAME.matcher(name).find()
                ? Optional.of("possible-secret")
                : Optional.empty();
    }

    private ArrayNode decodePacked(byte[] value, SchemaField schemaField) {
        ProtoReader reader = new ProtoReader(value);
        ArrayNode items = NODES.arrayNode();
        while (!reader.exhausted()) {
            ObjectNode item = switch (schemaField.protoType()) {
                case "double" -> decodeFixed64(reader.readFixed64(), schemaField);
                case "float" -> decodeFixed32(reader.readFixed32(), schemaField);
                case "fixed32", "sfixed32" -> decodeFixed32(reader.readFixed32(), schemaField);
                case "fixed64", "sfixed64" -> decodeFixed64(reader.readFixed64(), schemaField);
                case "bool", "sint32", "sint64", "int32", "uint32", "int64", "uint64", "enum" ->
                        decodeVarint(reader.readVarint(), schemaField);
                default -> throw new IllegalArgumentException("unsupported packed field type: " + schemaField.protoType());
            };
            applyMetadata(item, schemaField);
            item.put("packed", true);
            items.add(item);
        }
        return items;
    }

    private ObjectNode decodeAny(byte[] value, int depth) {
        ObjectNode envelope = decodeMessage(value, Optional.of(ANY_ENVELOPE_SCHEMA), depth);
        ObjectNode node = typed("message");
        node.put("messageType", "google.protobuf.Any");
        node.set("value", envelope);
        String typeUrl = envelope.path("f1").path("value").asText("");
        JsonNode valueField = envelope.path("f2");
        if (!typeUrl.isBlank() && valueField.has("value")) {
            String resolvedType = typeUrl.contains("/") ? typeUrl.substring(typeUrl.lastIndexOf('/') + 1) : typeUrl;
            Optional<SchemaMessage> innerSchema = schemas.message(resolvedType);
            if (innerSchema.isPresent()) {
                try {
                    byte[] innerBytes = Base64.getDecoder().decode(valueField.path("value").asText(""));
                    node.put("anyType", resolvedType);
                    node.set("anyValue", decodeMessage(innerBytes, innerSchema, depth + 1));
                } catch (RuntimeException ignored) {
                    // Leave the raw type_url/value envelope untouched when the embedded message cannot be decoded.
                }
            }
        }
        return node;
    }

    private void addReadableWellKnownView(ObjectNode node, String messageType, ObjectNode nested) {
        if (WRAPPER_TYPES.contains(messageType)) {
            JsonNode inner = nested.path("f1").path("value");
            if (!inner.isMissingNode()) {
                node.set("readable", inner);
            }
            return;
        }
        switch (messageType) {
            case "google.protobuf.Timestamp" -> readableTimestamp(nested).ifPresent(text -> node.put("readable", text));
            case "google.protobuf.Duration" -> readableDuration(nested).ifPresent(text -> node.put("readable", text));
            case "google.protobuf.FieldMask" -> node.put("readable", readableFieldMask(nested));
            case "google.protobuf.Struct" -> node.set("readable", readableStruct(nested));
            case "google.protobuf.Value" -> node.set("readable", readableValue(nested));
            case "google.protobuf.ListValue" -> node.set("readable", readableListValue(nested));
            default -> {
            }
        }
    }

    private static String readableFieldMask(ObjectNode nested) {
        List<String> paths = new ArrayList<>();
        for (JsonNode path : asIterable(nested.get("f1"))) {
            paths.add(path.path("value").asText(""));
        }
        return String.join(",", paths);
    }

    private static ObjectNode readableStruct(ObjectNode nested) {
        ObjectNode result = NODES.objectNode();
        for (JsonNode entry : asIterable(nested.get("f1"))) {
            JsonNode entryMessage = entry.path("value");
            String key = entryMessage.path("f1").path("value").asText("");
            JsonNode valueReadable = entryMessage.path("f2").path("readable");
            result.set(key, valueReadable.isMissingNode() ? NODES.nullNode() : valueReadable);
        }
        return result;
    }

    private static JsonNode readableValue(ObjectNode nested) {
        if (nested.has("f2")) {
            return nested.get("f2").path("value");
        }
        if (nested.has("f3")) {
            return nested.get("f3").path("value");
        }
        if (nested.has("f4")) {
            return nested.get("f4").path("value");
        }
        if (nested.has("f5")) {
            return nested.get("f5").path("readable");
        }
        if (nested.has("f6")) {
            return nested.get("f6").path("readable");
        }
        return NODES.nullNode();
    }

    private static ArrayNode readableListValue(ObjectNode nested) {
        ArrayNode result = NODES.arrayNode();
        for (JsonNode item : asIterable(nested.get("f1"))) {
            JsonNode itemReadable = item.path("readable");
            result.add(itemReadable.isMissingNode() ? NODES.nullNode() : itemReadable);
        }
        return result;
    }

    private static Iterable<JsonNode> asIterable(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        return node.isArray() ? node : List.of(node);
    }

    private static Optional<String> readableTimestamp(ObjectNode nested) {
        JsonNode secondsNode = nested.path("f1").path("value");
        if (!secondsNode.isIntegralNumber()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochSecond(secondsNode.asLong(), nested.path("f2").path("value").asLong(0)).toString());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static Optional<String> readableDuration(ObjectNode nested) {
        JsonNode secondsNode = nested.path("f1").path("value");
        if (!secondsNode.isIntegralNumber()) {
            return Optional.empty();
        }
        long seconds = secondsNode.asLong();
        long nanos = nested.path("f2").path("value").asLong(0);
        return Optional.of((seconds + nanos / 1_000_000_000.0) + "s");
    }

    private void writeField(ByteArrayOutputStream out, int fieldNumber, JsonNode field, SchemaField schemaField) {
        String type = field.path("type").asText(defaultType(schemaField));
        JsonNode value = field.path("value");
        switch (type) {
            case "varint", "int32", "int64", "uint32", "uint64", "enum" -> {
                writeVarint(out, ((long) fieldNumber << 3));
                writeVarint(out, resolveVarintValue(field, value, schemaField));
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

    private void writePackedField(ByteArrayOutputStream out, int fieldNumber, JsonNode values, SchemaField schemaField) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (JsonNode item : values) {
            writePackedValue(packed, item, schemaField);
        }
        writeLengthDelimited(out, fieldNumber, packed.toByteArray());
    }

    private void writePackedValue(ByteArrayOutputStream out, JsonNode field, SchemaField schemaField) {
        String type = field.path("type").asText(defaultType(schemaField));
        JsonNode value = field.path("value");
        switch (type) {
            case "varint", "int32", "int64", "uint32", "uint64", "enum" -> writeVarint(out, resolveVarintValue(field, value, schemaField));
            case "bool" -> writeVarint(out, value.asBoolean() ? 1 : 0);
            case "sint32", "sint64" -> writeVarint(out, encodeZigZag(value.asLong()));
            case "fixed64", "sfixed64" -> writeLittleEndian64(out, value.asLong());
            case "double" -> writeLittleEndian64(out, Double.doubleToRawLongBits(value.asDouble()));
            case "fixed32", "sfixed32" -> writeLittleEndian32(out, value.asLong());
            case "float" -> writeLittleEndian32(out, Float.floatToRawIntBits((float) value.asDouble()));
            default -> throw new IllegalArgumentException("unsupported packed field type: " + type);
        }
    }

    private static ObjectNode typed(String type) {
        ObjectNode node = NODES.objectNode();
        node.put("type", type);
        return node;
    }

    private static void applyMetadata(JsonNode field, SchemaField schemaField) {
        if (schemaField == null || !field.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) field;
        object.put("name", schemaField.name());
        object.put("protoType", schemaField.protoType());
        object.put("repeated", schemaField.repeated());
        object.put("packed", schemaField.packed());
        if (!schemaField.messageType().isBlank()) {
            object.put("messageType", schemaField.messageType());
        }
        if (!schemaField.enumType().isBlank()) {
            object.put("enumType", schemaField.enumType());
        }
        if (!schemaField.oneof().isBlank()) {
            object.put("oneof", schemaField.oneof());
        }
        if (schemaField.map()) {
            object.put("map", true);
        }
        if (!object.has("hint")) {
            nameHint(schemaField.name()).ifPresent(hint -> object.put("hint", hint));
        }
    }

    private static String defaultType(SchemaField schemaField) {
        return schemaField == null ? "" : schemaField.protoType();
    }

    private static void appendField(ObjectNode object, String name, JsonNode value) {
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

    private static int resolveFieldNumber(SchemaMessage schema, String key) {
        if (FIELD_NAME.matcher(key).matches()) {
            return Integer.parseInt(key.substring(1));
        }
        if (schema != null) {
            SchemaField named = schema.fieldByName(key);
            if (named != null) {
                return named.number();
            }
        }
        throw new IllegalArgumentException("field names must use f<number>: " + key);
    }

    private long resolveVarintValue(JsonNode field, JsonNode value, SchemaField schemaField) {
        if (schemaField != null && !schemaField.enumType().isBlank() && field.hasNonNull("enumName")) {
            OptionalLong resolved = schemas.enumValue(schemaField.enumType(), field.get("enumName").asText());
            if (resolved.isPresent()) {
                return resolved.getAsLong();
            }
        }
        return value.asLong();
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
