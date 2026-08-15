package com.github.burpgrpccodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort dump of this extension's currently loaded schema (from .proto
 * files, a binary FileDescriptorSet, or Server Reflection) back into
 * .proto-like text, so a target's discovered schema can be saved locally.
 * Message and service names are shortened to their simple (non-qualified)
 * name for the declaration itself, which can collide when two loaded
 * packages define a same-named type; field and rpc types are always emitted
 * fully-qualified (a leading '.') so they stay unambiguous regardless of
 * that collision. The seeded {@code google.protobuf.*} well-known-type
 * helpers are omitted, since they aren't part of any target's own schema.
 */
final class SchemaProtoExporter {
    private SchemaProtoExporter() {
    }

    static String export(List<SchemaMessage> messages, List<SchemaMethod> methods) {
        StringBuilder out = new StringBuilder("syntax = \"proto3\";\n\n");
        for (SchemaMessage message : messages) {
            if (message.typeName().startsWith("google.protobuf.")) {
                continue;
            }
            out.append("// ").append(message.typeName()).append('\n');
            out.append(renderMessage(message)).append('\n');
        }
        for (String service : groupServices(methods).values()) {
            out.append(service).append('\n');
        }
        return out.toString();
    }

    private static String renderMessage(SchemaMessage message) {
        StringBuilder body = new StringBuilder("message ").append(simpleName(message.typeName())).append(" {\n");
        message.fieldsByNumber().keySet().stream().sorted().forEach(number -> {
            SchemaField field = message.field(number);
            body.append("  ");
            if (field.repeated()) {
                body.append("repeated ");
            }
            body.append(fieldTypeReference(field)).append(' ').append(field.name())
                    .append(" = ").append(field.number()).append(';');
            if (field.map()) {
                body.append(" // map entry");
            }
            body.append('\n');
        });
        body.append("}\n");
        return body.toString();
    }

    private static String fieldTypeReference(SchemaField field) {
        if (!field.messageType().isBlank()) {
            return "." + field.messageType();
        }
        if (!field.enumType().isBlank()) {
            return "." + field.enumType();
        }
        return field.protoType();
    }

    private static Map<String, String> groupServices(List<SchemaMethod> methods) {
        Map<String, StringBuilder> byService = new LinkedHashMap<>();
        for (SchemaMethod method : methods) {
            String path = method.path();
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash <= 0) {
                continue;
            }
            String fullServiceName = path.substring(1, lastSlash);
            String methodName = path.substring(lastSlash + 1);
            StringBuilder body = byService.computeIfAbsent(fullServiceName,
                    key -> new StringBuilder("// ").append(key).append('\n')
                            .append("service ").append(simpleName(key)).append(" {\n"));
            body.append("  rpc ").append(methodName)
                    .append(" (.").append(method.requestType()).append(") returns (.")
                    .append(method.responseType()).append(");\n");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : byService.entrySet()) {
            result.put(entry.getKey(), entry.getValue().append("}\n").toString());
        }
        return result;
    }

    private static String simpleName(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot < 0 ? fullyQualified : fullyQualified.substring(lastDot + 1);
    }
}
