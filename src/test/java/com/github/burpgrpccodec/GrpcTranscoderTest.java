package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
