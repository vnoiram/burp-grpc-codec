package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort reverse-engineering of a {@code .proto} message definition from
 * this extension's decoded gRPC JSON, for traffic observed without .proto
 * files, a binary FileDescriptorSet, or Server Reflection available. Field
 * types are inferred from the wire type alone unless schema metadata
 * ({@code protoType}) is already present in the decoded JSON, so an inferred
 * type is a guess (e.g. a varint could be int32, uint64, bool, or an enum).
 */
final class ProtoStubGenerator {
    private static final Pattern FIELD_NAME = Pattern.compile("f([1-9][0-9]*)");

    private ProtoStubGenerator() {
    }

    static String generate(String rootMessageName, JsonNode message) {
        Map<String, String> messages = new LinkedHashMap<>();
        collectMessage(rootMessageName, message, messages, new int[1]);
        StringBuilder out = new StringBuilder("syntax = \"proto3\";\n\n");
        for (String body : messages.values()) {
            out.append(body).append('\n');
        }
        return out.toString();
    }

    private static void collectMessage(String name, JsonNode message, Map<String, String> messages, int[] anonymousCounter) {
        if (messages.containsKey(name) || message == null || !message.isObject()) {
            return;
        }
        // Reserve the name (and its position in output order) before recursing,
        // in case a nested field references the same message type again.
        messages.put(name, "");
        StringBuilder body = new StringBuilder("message ").append(name).append(" {\n");
        int fallbackNumber = 1;
        Iterator<Map.Entry<String, JsonNode>> fields = message.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            boolean repeated = value.isArray();
            JsonNode sample = repeated ? (value.isEmpty() ? null : value.get(0)) : value;
            int number = fieldNumber(key, fallbackNumber++);
            FieldType fieldType = sample == null
                    ? new FieldType("bytes", "empty repeated field, type unknown")
                    : inferType(sample, name, messages, anonymousCounter);
            body.append("  ");
            if (repeated) {
                body.append("repeated ");
            }
            body.append(fieldType.protoType).append(' ').append(fieldName(sample, key)).append(" = ").append(number).append(';');
            if (fieldType.comment != null) {
                body.append(" // ").append(fieldType.comment);
            }
            body.append('\n');
        }
        body.append("}\n");
        messages.put(name, body.toString());
    }

    private static int fieldNumber(String key, int fallback) {
        Matcher matcher = FIELD_NAME.matcher(key);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // fall through to the fallback below
            }
        }
        return fallback;
    }

    private static String fieldName(JsonNode sample, String key) {
        return sample != null && sample.hasNonNull("name") ? sample.get("name").asText() : key;
    }

    private static FieldType inferType(JsonNode field, String parentName, Map<String, String> messages, int[] anonymousCounter) {
        if (field.hasNonNull("protoType")) {
            if (field.hasNonNull("messageType") && field.path("value").isObject()) {
                String nestedName = simpleTypeName(field.get("messageType").asText());
                collectMessage(nestedName, field.get("value"), messages, anonymousCounter);
                return new FieldType(nestedName, null);
            }
            if (field.hasNonNull("enumType")) {
                return new FieldType("int32", "enum " + field.get("enumType").asText());
            }
            return new FieldType(field.get("protoType").asText(), null);
        }
        String type = field.path("type").asText("");
        return switch (type) {
            case "string" -> new FieldType("string", null);
            case "bytes" -> new FieldType("bytes", null);
            case "bool" -> new FieldType("bool", null);
            case "fixed32" -> new FieldType("fixed32", "or float/sfixed32");
            case "fixed64" -> new FieldType("fixed64", "or double/sfixed64");
            case "varint" -> new FieldType("int64", "or int32/uint32/uint64/bool/enum");
            case "message" -> {
                String label = field.hasNonNull("name") ? field.get("name").asText() : "Nested" + (++anonymousCounter[0]);
                String nestedName = parentName + "_" + capitalize(label);
                collectMessage(nestedName, field.path("value"), messages, anonymousCounter);
                yield new FieldType(nestedName, null);
            }
            default -> new FieldType("bytes", "unrecognized wire type");
        };
    }

    private static String simpleTypeName(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot < 0 ? fullyQualified : fullyQualified.substring(lastDot + 1);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record FieldType(String protoType, String comment) {
    }
}
