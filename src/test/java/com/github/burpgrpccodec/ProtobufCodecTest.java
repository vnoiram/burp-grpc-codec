package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void rejectsTruncatedLengthDelimitedFieldWithLargeDeclaredLength() {
        byte[] protobuf = new byte[] {
                0x0a,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07
        };

        assertThrows(IllegalArgumentException.class, () -> new ProtobufCodec().decodeMessage(protobuf));
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

    @Test
    void addsSchemaMetadataWithoutChangingWireFormat() {
        SchemaMessage schema = new SchemaMessage("demo.Hello",
                Map.of(1, new SchemaField(1, "greeting", "string", "", "", false, false)),
                Map.of());
        byte[] protobuf = new byte[] {0x0a, 0x02, 'o', 'k'};

        ProtobufCodec codec = new ProtobufCodec();
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);

        assertEquals("greeting", decoded.get("f1").get("name").asText());
        assertEquals("string", decoded.get("f1").get("protoType").asText());
        assertArrayEquals(protobuf, codec.encodeMessage(decoded, schema));
    }

    @Test
    void loadsSchemaMetadataFromProtoSource() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Hello {
                  string greeting = 1;
                }
                """);

        SchemaMessage schema = registry.message("demo.Hello").orElseThrow();
        ObjectNode decoded = new ProtobufCodec().decodeMessage(new byte[] {0x0a, 0x02, 'o', 'k'}, schema);

        assertEquals("greeting", decoded.get("f1").get("name").asText());
    }

    @Test
    void treatsProtoMessageFieldsAsMessageTypes() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Child {
                  string name = 1;
                }
                message Parent {
                  Child child = 1;
                }
                """);

        SchemaField field = registry.message("demo.Parent").orElseThrow().field(1);

        assertEquals("demo.Child", field.messageType());
    }

    @Test
    void usesSchemaMetadataForNestedMessages() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Child {
                  string name = 1;
                }
                message Parent {
                  Child child = 1;
                }
                """);
        byte[] protobuf = new byte[] {
                0x0a, 0x04,
                0x0a, 0x02, 'o', 'k'
        };

        ObjectNode decoded = new ProtobufCodec(registry)
                .decodeMessage(protobuf, registry.message("demo.Parent").orElseThrow());

        JsonNode child = decoded.get("f1").get("value").get("f1");
        assertEquals("name", child.get("name").asText());
        assertEquals("ok", child.get("value").asText());
    }
}
