package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcTranscoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void decodesAndEncodesGrpcFrame() throws Exception {
        byte[] body = new byte[] {
                0, 0, 0, 0, 4,
                0x0a, 0x02, 'o', 'k'
        };
        GrpcTranscoder transcoder = new GrpcTranscoder();

        byte[] json = transcoder.decode(body, new HttpHeaders("application/grpc"));
        JsonNode root = JSON.readTree(json);

        assertEquals("grpc", root.get("_format").asText());
        assertEquals("ok", root.get("messages").get(0).get("message").get("f1").get("value").asText());
        assertArrayEquals(body, transcoder.encode(json, new HttpHeaders("application/grpc")));
    }

    @Test
    void decodesAndEncodesGrpcWebText() {
        byte[] framed = new byte[] {
                0, 0, 0, 0, 2,
                0x08, 0x01
        };
        byte[] body = Base64.getEncoder().encode(framed);

        GrpcTranscoder transcoder = new GrpcTranscoder();
        byte[] json = transcoder.decode(body, new HttpHeaders("application/grpc-web-text"));
        byte[] encoded = transcoder.encode(json, new HttpHeaders("application/grpc-web-text"));

        assertTrue(Arrays.equals(body, encoded));
        assertEquals(new String(body, StandardCharsets.US_ASCII), new String(encoded, StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsTruncatedGrpcFrameWithLargeDeclaredLength() {
        byte[] body = new byte[] {
                0,
                0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };

        assertThrows(IllegalArgumentException.class,
                () -> new GrpcTranscoder().decode(body, new HttpHeaders("application/grpc")));
    }

    @Test
    void preservesGrpcWebTrailers() throws Exception {
        byte[] body = new byte[] {
                0, 0, 0, 0, 2,
                0x08, 0x01,
                (byte) 0x80, 0, 0, 0, 16,
                'g', 'r', 'p', 'c', '-', 's', 't', 'a', 't', 'u', 's', ':', ' ', '0', '\r', '\n'
        };

        GrpcTranscoder transcoder = new GrpcTranscoder();
        byte[] json = transcoder.decode(body, new HttpHeaders("application/grpc-web"));

        assertEquals(1, JSON.readTree(json).get("trailers").size());
        assertArrayEquals(body, transcoder.encode(json, new HttpHeaders("application/grpc-web")));
    }

    @Test
    void decodesAndEncodesGzipCompressedGrpcMessage() throws Exception {
        byte[] protobuf = new byte[] {0x0a, 0x02, 'o', 'k'};
        byte[] compressed = gzip(protobuf);
        byte[] body = new byte[5 + compressed.length];
        body[0] = 1;
        body[4] = (byte) compressed.length;
        System.arraycopy(compressed, 0, body, 5, compressed.length);

        GrpcTranscoder transcoder = new GrpcTranscoder();
        byte[] json = transcoder.decode(body, new HttpHeaders("application/grpc", "gzip"));

        JsonNode root = JSON.readTree(json);
        assertEquals("gzip", root.get("messages").get(0).get("compression").asText());
        assertEquals("ok", root.get("messages").get(0).get("message").get("f1").get("value").asText());
        assertArrayEquals(body, transcoder.encode(json, new HttpHeaders("application/grpc", "gzip")));
    }

    @Test
    void mapsGrpcPathToRequestAndResponseMessageTypes() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                service Greeter {
                  rpc SayHello (HelloRequest) returns (HelloResponse);
                }
                message HelloRequest {
                  string request_name = 1;
                }
                message HelloResponse {
                  string response_name = 1;
                }
                """);
        GrpcTranscoder transcoder = new GrpcTranscoder(registry, null);
        byte[] body = new byte[] {
                0, 0, 0, 0, 4,
                0x0a, 0x02, 'o', 'k'
        };

        JsonNode request = JSON.readTree(transcoder.decode(
                body,
                new HttpHeaders("application/grpc", "", "/demo.Greeter/SayHello", false)));
        JsonNode response = JSON.readTree(transcoder.decode(
                body,
                new HttpHeaders("application/grpc", "", "/demo.Greeter/SayHello", true)));

        assertEquals("demo.HelloRequest", request.get("messageType").asText());
        assertEquals("request_name", request.get("messages").get(0).get("message").get("f1").get("name").asText());
        assertEquals("demo.HelloResponse", response.get("messageType").asText());
        assertEquals("response_name", response.get("messages").get(0).get("message").get("f1").get("name").asText());
    }

    @Test
    void decodesAndEncodesDeflateCompressedGrpcMessage() throws Exception {
        byte[] protobuf = new byte[] {0x0a, 0x02, 'o', 'k'};
        byte[] compressed = deflate(protobuf);
        byte[] body = new byte[5 + compressed.length];
        body[0] = 1;
        body[4] = (byte) compressed.length;
        System.arraycopy(compressed, 0, body, 5, compressed.length);

        GrpcTranscoder transcoder = new GrpcTranscoder();
        byte[] json = transcoder.decode(body, new HttpHeaders("application/grpc", "deflate"));

        JsonNode root = JSON.readTree(json);
        assertEquals("deflate", root.get("messages").get(0).get("compression").asText());
        assertEquals("ok", root.get("messages").get(0).get("message").get("f1").get("value").asText());
        assertArrayEquals(body, transcoder.encode(json, new HttpHeaders("application/grpc", "deflate")));
    }

    @Test
    void exposesGrpcStatusAndMessageFromHeaders() throws Exception {
        byte[] body = new byte[] {
                0, 0, 0, 0, 4,
                0x0a, 0x02, 'o', 'k'
        };
        GrpcTranscoder transcoder = new GrpcTranscoder();

        byte[] json = transcoder.decode(body, new HttpHeaders("application/grpc", "", "", true, "5", "not found"));
        JsonNode root = JSON.readTree(json);

        assertEquals("5", root.get("grpcStatus").asText());
        assertEquals("NOT_FOUND", root.get("grpcStatusName").asText());
        assertEquals("not found", root.get("grpcMessage").asText());
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] bytes) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
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
}
