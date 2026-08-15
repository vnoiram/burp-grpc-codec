package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Best-effort conversion of this extension's decoded ({@code f<n>}/type/value)
 * gRPC JSON into a plain JSON object suitable as a {@code grpcurl -d} payload,
 * and assembly of the resulting grpcurl command line, so a captured request
 * can be replayed outside Burp.
 */
final class GrpcCurlFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GrpcCurlFormatter() {
    }

    static String buildCommand(String hostAndPort, boolean tls, String methodPath, JsonNode messageJson) {
        String path = methodPath.startsWith("/") ? methodPath.substring(1) : methodPath;
        String payload;
        try {
            payload = JSON.writeValueAsString(simplify(messageJson));
        } catch (Exception ex) {
            payload = "{}";
        }
        StringBuilder command = new StringBuilder("grpcurl ");
        if (!tls) {
            command.append("-plaintext ");
        }
        command.append("-d '").append(payload.replace("'", "'\\''")).append("' ");
        command.append(hostAndPort).append(' ').append(path);
        return command.toString();
    }

    /**
     * Unwraps this extension's typed field envelopes ({@code {"type":...,
     * "value":...}}) into plain JSON values, recursing into nested messages.
     * This is a best-effort approximation of proto3 JSON, not a spec-
     * compliant mapping (e.g. int64 fields are emitted as JSON numbers here,
     * not decimal strings).
     */
    static JsonNode simplify(JsonNode decodedMessage) {
        if (decodedMessage == null || decodedMessage.isMissingNode() || decodedMessage.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (decodedMessage.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : decodedMessage) {
                array.add(simplify(item));
            }
            return array;
        }
        if (!decodedMessage.isObject()) {
            return decodedMessage;
        }
        // A field envelope has a "type" key; a decoded message has plain f<n>/name keys.
        if (decodedMessage.has("type")) {
            return simplifyField(decodedMessage);
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        decodedMessage.fields().forEachRemaining(entry -> result.set(entry.getKey(), simplify(entry.getValue())));
        return result;
    }

    private static JsonNode simplifyField(JsonNode field) {
        if ("message".equals(field.path("type").asText(""))) {
            return simplify(field.path("value"));
        }
        JsonNode value = field.get("value");
        return value == null ? JsonNodeFactory.instance.nullNode() : value;
    }
}
