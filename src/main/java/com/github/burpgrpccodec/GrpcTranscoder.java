package com.github.burpgrpccodec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class GrpcTranscoder {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final ProtobufCodec protobuf = new ProtobufCodec();

    boolean isCandidate(byte[] body, HttpHeaders headers) {
        if (body.length == 0) {
            return false;
        }
        if (headers.isGrpc() || headers.isGrpcWeb()) {
            return true;
        }
        return protobuf.looksLikeProtobuf(body);
    }

    byte[] decode(byte[] body, HttpHeaders headers) {
        Envelope envelope = decodeEnvelope(body, headers);
        ObjectNode root = JSON.createObjectNode();
        root.put("_format", envelope.format);
        ArrayNode messages = root.putArray("messages");
        for (GrpcMessage message : envelope.messages) {
            ObjectNode node = messages.addObject();
            node.put("compressed", message.compressed);
            if (message.compressed) {
                node.put("compressedBytes", Base64.getEncoder().encodeToString(message.payload));
            } else {
                node.set("message", protobuf.decodeMessage(message.payload));
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
        try {
            JsonNode root = JSON.readTree(jsonBytes);
            String format = root.path("_format").asText(detectFormat(headers));
            List<GrpcMessage> messages = new ArrayList<>();
            for (JsonNode item : root.withArray("messages")) {
                boolean compressed = item.path("compressed").asBoolean(false);
                byte[] payload = compressed
                        ? Base64.getDecoder().decode(item.path("compressedBytes").asText(""))
                        : protobuf.encodeMessage((ObjectNode) item.path("message"));
                messages.add(new GrpcMessage(compressed, payload));
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

    private Envelope decodeEnvelope(byte[] body, HttpHeaders headers) {
        String format = detectFormat(headers);
        byte[] transportBody = body;
        if ("grpc-web-text".equals(format)) {
            transportBody = Base64.getMimeDecoder().decode(new String(body, StandardCharsets.US_ASCII));
        }
        if ("grpc".equals(format) || "grpc-web".equals(format) || "grpc-web-text".equals(format)) {
            return decodeFrames(format, transportBody);
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

    private Envelope decodeFrames(String format, byte[] body) {
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
                messages.add(new GrpcMessage((flags & 0x01) != 0, payload));
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

    private static byte[] toPrettyBytes(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Envelope(String format, List<GrpcMessage> messages, List<byte[]> trailers) {
    }

    private record GrpcMessage(boolean compressed, byte[] payload) {
    }
}
