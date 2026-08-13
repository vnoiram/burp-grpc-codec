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

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }
}
