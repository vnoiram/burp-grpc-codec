package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void decodesAndEncodesSchemaLessMessage() throws Exception {
        byte[] protobuf = new byte[] {
                0x0a, 0x05, 'h', 'e', 'l', 'l', 'o',
                0x10, 0x7b
        };

        ProtobufCodec codec = new ProtobufCodec();
        ObjectNode decoded = codec.decodeMessage(protobuf);

        assertEquals("string", decoded.get("f1").get("type").asText());
        assertEquals("hello", decoded.get("f1").get("value").asText());
        assertEquals("varint", decoded.get("f2").get("type").asText());
        assertEquals(123, decoded.get("f2").get("value").asInt());
        assertArrayEquals(protobuf, codec.encodeMessage(decoded));
    }

    @Test
    void preservesOpaqueBytesAsBase64() {
        byte[] protobuf = new byte[] {
                0x0a, 0x03, 0x00, 0x01, 0x02
        };

        ObjectNode decoded = new ProtobufCodec().decodeMessage(protobuf);

        assertEquals("bytes", decoded.get("f1").get("type").asText());
        assertEquals(Base64.getEncoder().encodeToString(new byte[] {0, 1, 2}), decoded.get("f1").get("value").asText());
    }

    @Test
    void decodesRepeatedFieldsAsArray() {
        byte[] protobuf = new byte[] {0x08, 0x01, 0x08, 0x02};

        JsonNode field = new ProtobufCodec().decodeMessage(protobuf).get("f1");

        assertTrue(field.isArray());
        assertEquals(2, field.size());
    }

    @Test
    void encodesJsonMessage() throws Exception {
        ObjectNode message = (ObjectNode) JSON.readTree("""
                {
                  "f1": {"type": "string", "value": "edited"},
                  "f2": {"type": "varint", "value": 321}
                }
                """);

        ObjectNode decoded = new ProtobufCodec().decodeMessage(new ProtobufCodec().encodeMessage(message));

        assertEquals("edited", decoded.get("f1").get("value").asText());
        assertEquals(321, decoded.get("f2").get("value").asInt());
    }
}
