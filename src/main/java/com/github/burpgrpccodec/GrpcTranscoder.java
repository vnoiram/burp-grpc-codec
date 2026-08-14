package com.github.burpgrpccodec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

final class GrpcTranscoder {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT_JSON = new ObjectMapper();
    private static final Map<Integer, String> GRPC_STATUS_NAMES = Map.ofEntries(
            Map.entry(0, "OK"), Map.entry(1, "CANCELLED"), Map.entry(2, "UNKNOWN"),
            Map.entry(3, "INVALID_ARGUMENT"), Map.entry(4, "DEADLINE_EXCEEDED"), Map.entry(5, "NOT_FOUND"),
            Map.entry(6, "ALREADY_EXISTS"), Map.entry(7, "PERMISSION_DENIED"), Map.entry(8, "RESOURCE_EXHAUSTED"),
            Map.entry(9, "FAILED_PRECONDITION"), Map.entry(10, "ABORTED"), Map.entry(11, "OUT_OF_RANGE"),
            Map.entry(12, "UNIMPLEMENTED"), Map.entry(13, "INTERNAL"), Map.entry(14, "UNAVAILABLE"),
            Map.entry(15, "DATA_LOSS"), Map.entry(16, "UNAUTHENTICATED"));
    private final ProtobufCodec protobuf;
    private final SchemaRegistry schemas;
    private final ExtensionSettings settings;

    GrpcTranscoder() {
        this(new SchemaRegistry(), null);
    }

    GrpcTranscoder(SchemaRegistry schemas, ExtensionSettings settings) {
        this.schemas = schemas;
        this.settings = settings;
        this.protobuf = new ProtobufCodec(schemas, settings == null ? 24 : settings.maxDepth());
    }

    boolean verboseLogging() {
        return settings != null && settings.verboseLogging();
    }

    boolean autoSelectTab() {
        return settings != null && settings.autoSelectTab();
    }

    String schemaSummary() {
        return schemas.messageCount() + " message types, " + schemas.methodCount() + " methods";
    }

    boolean isDeclaredGrpcOrProtobuf(HttpHeaders headers) {
        return headers.isGrpc() || headers.isGrpcWeb() || headers.isProtobuf();
    }

    boolean isCandidate(byte[] body, HttpHeaders headers) {
        if (body.length == 0) {
            return false;
        }
        if (isDeclaredGrpcOrProtobuf(headers)) {
            return true;
        }
        ExtensionSettings.RawDetection mode = settings == null
                ? ExtensionSettings.RawDetection.BROAD
                : settings.rawDetection();
        if (mode == ExtensionSettings.RawDetection.OFF) {
            return false;
        }
        if (mode == ExtensionSettings.RawDetection.STRICT && !hasLikelyBinaryProtobufShape(body)) {
            return false;
        }
        return protobuf.looksLikeProtobuf(body);
    }

    byte[] decode(byte[] body, HttpHeaders headers) {
        applyLiveSettings();
        Envelope envelope = decodeEnvelope(body, headers);
        Optional<SchemaMessage> schema = schemaFor(headers, envelope.format, "");
        ObjectNode root = JSON.createObjectNode();
        root.put("_format", envelope.format);
        schema.ifPresent(message -> root.put("messageType", message.typeName()));
        if (!headers.grpcStatus().isBlank()) {
            root.put("grpcStatus", headers.grpcStatus());
            grpcStatusName(headers.grpcStatus()).ifPresent(name -> root.put("grpcStatusName", name));
        }
        if (!headers.grpcMessage().isBlank()) {
            root.put("grpcMessage", headers.grpcMessage());
        }
        ArrayNode messages = root.putArray("messages");
        for (GrpcMessage message : envelope.messages) {
            ObjectNode node = messages.addObject();
            node.put("compressed", message.compressed);
            if (message.compression != null) {
                node.put("compression", message.compression);
            }
            if (message.compressed && isSupportedCompression(message.compression)) {
                decodeCompressedMessage(schema, message, node);
            } else if (message.compressed) {
                node.put("compressedBytes", Base64.getEncoder().encodeToString(message.payload));
            } else {
                byte[] payload = message.payload;
                node.set("message", schema.map(schemaMessage -> protobuf.decodeMessage(payload, schemaMessage))
                        .orElseGet(() -> protobuf.decodeMessage(payload)));
            }
        }
        if (!envelope.trailers.isEmpty()) {
            ArrayNode trailers = root.putArray("trailers");
            for (byte[] trailer : envelope.trailers) {
                trailers.add(Base64.getEncoder().encodeToString(trailer));
            }
        }
        return toPrettyBytes(root);
    }

    byte[] encode(byte[] jsonBytes, HttpHeaders headers) {
        applyLiveSettings();
        try {
            JsonNode root = JSON.readTree(jsonBytes);
            String format = root.path("_format").asText(detectFormat(headers));
            Optional<SchemaMessage> schema = schemaFor(headers, format, root.path("messageType").asText(""));
            List<GrpcMessage> messages = new ArrayList<>();
            for (JsonNode item : root.withArray("messages")) {
                boolean compressed = item.path("compressed").asBoolean(false);
                String compression = item.path("compression").asText(compressionName(headers));
                byte[] payload = encodePayload(item, schema, compressed, compression);
                messages.add(new GrpcMessage(compressed, payload, compression.isBlank() ? null : compression));
            }
            List<byte[]> trailers = new ArrayList<>();
            for (JsonNode item : root.withArray("trailers")) {
                trailers.add(Base64.getDecoder().decode(item.asText()));
            }
            return encodeEnvelope(new Envelope(format, messages, trailers));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot encode edited gRPC JSON: " + ex.getMessage(), ex);
        }
    }

    void reloadSchemas() {
        if (settings != null) {
            settings.saveSnapshot();
            schemas.reload(settings);
        }
    }

    private void decodeCompressedMessage(Optional<SchemaMessage> schema, GrpcMessage message, ObjectNode node) {
        try {
            byte[] decompressed = decompress(message.payload, message.compression);
            node.set("message", schema.map(schemaMessage -> protobuf.decodeMessage(decompressed, schemaMessage))
                    .orElseGet(() -> protobuf.decodeMessage(decompressed)));
        } catch (RuntimeException ex) {
            node.put("compressedBytes", Base64.getEncoder().encodeToString(message.payload));
        }
    }

    private byte[] encodePayload(JsonNode item, Optional<SchemaMessage> schema, boolean compressed, String compression) {
        if (compressed && item.has("compressedBytes")) {
            return Base64.getDecoder().decode(item.path("compressedBytes").asText(""));
        }
        byte[] payload = schema.map(schemaMessage -> protobuf.encodeMessage((ObjectNode) item.path("message"), schemaMessage))
                .orElseGet(() -> protobuf.encodeMessage((ObjectNode) item.path("message")));
        if (compressed && isSupportedCompression(compression)) {
            return compress(payload, compression);
        }
        return payload;
    }

    private static Optional<String> grpcStatusName(String status) {
        try {
            return Optional.ofNullable(GRPC_STATUS_NAMES.get(Integer.parseInt(status.trim())));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static boolean isSupportedCompression(String compression) {
        return "gzip".equals(compression) || "deflate".equals(compression);
    }

    private static String compressionName(HttpHeaders headers) {
        if (headers.isGzipEncoded()) {
            return "gzip";
        }
        if (headers.isDeflateEncoded()) {
            return "deflate";
        }
        return "";
    }

    private static byte[] decompress(byte[] bytes, String compression) {
        return "deflate".equals(compression) ? inflate(bytes) : gunzip(bytes);
    }

    private static byte[] compress(byte[] bytes, String compression) {
        return "deflate".equals(compression) ? deflate(bytes) : gzip(bytes);
    }

    private Envelope decodeEnvelope(byte[] body, HttpHeaders headers) {
        String format = detectFormat(headers);
        byte[] transportBody = body;
        if ("grpc-web-text".equals(format)) {
            transportBody = Base64.getMimeDecoder().decode(new String(body, StandardCharsets.US_ASCII));
        }
        if ("grpc".equals(format) || "grpc-web".equals(format) || "grpc-web-text".equals(format)) {
            return decodeFrames(format, transportBody, headers);
        }
        return new Envelope("protobuf", List.of(new GrpcMessage(false, body)), List.of());
    }

    private String detectFormat(HttpHeaders headers) {
        if (headers.isGrpcWebText()) {
            return "grpc-web-text";
        }
        if (headers.isGrpcWeb()) {
            return "grpc-web";
        }
        if (headers.isGrpc()) {
            return "grpc";
        }
        return "protobuf";
    }

    private Envelope decodeFrames(String format, byte[] body, HttpHeaders headers) {
        List<GrpcMessage> messages = new ArrayList<>();
        List<byte[]> trailers = new ArrayList<>();
        int offset = 0;
        while (offset + 5 <= body.length) {
            int flags = body[offset] & 0xff;
            boolean trailerFrame = (flags & 0x80) != 0;
            int length = ((body[offset + 1] & 0xff) << 24)
                    | ((body[offset + 2] & 0xff) << 16)
                    | ((body[offset + 3] & 0xff) << 8)
                    | (body[offset + 4] & 0xff);
            offset += 5;
            if (length < 0 || length > body.length - offset) {
                throw new IllegalArgumentException("Invalid gRPC frame length");
            }
            byte[] payload = new byte[length];
            System.arraycopy(body, offset, payload, 0, length);
            offset += length;
            if (!trailerFrame) {
                boolean compressed = (flags & 0x01) != 0;
                String compression = compressionName(headers);
                messages.add(new GrpcMessage(compressed, payload, compressed && !compression.isBlank() ? compression : null));
            } else {
                trailers.add(payload);
            }
        }
        if (offset != body.length) {
            throw new IllegalArgumentException("Trailing bytes after gRPC frames");
        }
        return new Envelope(format, messages, trailers);
    }

    private byte[] encodeEnvelope(Envelope envelope) {
        byte[] framed;
        if ("protobuf".equals(envelope.format)) {
            if (envelope.messages.size() != 1) {
                throw new IllegalArgumentException("raw protobuf format expects exactly one message");
            }
            framed = envelope.messages.get(0).payload;
        } else {
            framed = encodeFrames(envelope.messages, envelope.trailers);
        }
        if ("grpc-web-text".equals(envelope.format)) {
            return Base64.getEncoder().encode(framed);
        }
        return framed;
    }

    private byte[] encodeFrames(List<GrpcMessage> messages, List<byte[]> trailers) {
        int total = messages.stream().mapToInt(message -> 5 + message.payload.length).sum()
                + trailers.stream().mapToInt(trailer -> 5 + trailer.length).sum();
        byte[] out = new byte[total];
        int offset = 0;
        for (GrpcMessage message : messages) {
            out[offset] = (byte) (message.compressed ? 1 : 0);
            writeFrameLength(out, offset, message.payload.length);
            System.arraycopy(message.payload, 0, out, offset + 5, message.payload.length);
            offset += 5 + message.payload.length;
        }
        for (byte[] trailer : trailers) {
            out[offset] = (byte) 0x80;
            writeFrameLength(out, offset, trailer.length);
            System.arraycopy(trailer, 0, out, offset + 5, trailer.length);
            offset += 5 + trailer.length;
        }
        return out;
    }

    private void writeFrameLength(byte[] out, int offset, int length) {
        out[offset + 1] = (byte) (length >>> 24);
        out[offset + 2] = (byte) (length >>> 16);
        out[offset + 3] = (byte) (length >>> 8);
        out[offset + 4] = (byte) length;
    }

    private Optional<SchemaMessage> schemaFor(HttpHeaders headers, String format, String explicitType) {
        if (!explicitType.isBlank()) {
            return schemas.message(explicitType);
        }
        Optional<SchemaMessage> pathMapped = schemas.messageForPath(headers.grpcPath(), headers.response());
        if (pathMapped.isPresent()) {
            return pathMapped;
        }
        if (settings == null) {
            return Optional.empty();
        }
        String type = "protobuf".equals(format) || headers.isGrpc()
                ? settings.defaultRequestType()
                : settings.defaultResponseType();
        return schemas.message(type);
    }

    private static boolean hasLikelyBinaryProtobufShape(byte[] body) {
        return body.length >= 2 && (body[0] & 0x07) <= 5 && ((body[0] >>> 3) > 0);
    }

    private static byte[] gunzip(byte[] bytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Cannot decompress gzip message", ex);
        }
    }

    private static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Cannot compress gzip message", ex);
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] bytes) {
        Inflater inflater = new Inflater();
        inflater.setInput(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException ex) {
            throw new IllegalArgumentException("Cannot decompress deflate message", ex);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }

    private void applyLiveSettings() {
        if (settings != null) {
            protobuf.setMaxRecursion(settings.maxDepth());
        }
    }

    private byte[] toPrettyBytes(JsonNode node) {
        try {
            ObjectMapper mapper = settings != null && !settings.prettyJson() ? COMPACT_JSON : JSON;
            return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Envelope(String format, List<GrpcMessage> messages, List<byte[]> trailers) {
    }

    private record GrpcMessage(boolean compressed, byte[] payload, String compression) {
        private GrpcMessage(boolean compressed, byte[] payload) {
            this(compressed, payload, null);
        }
    }
}
