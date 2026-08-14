package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DescriptorProtos;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void decodesAndEncodesPackedRepeatedScalars() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Numbers {
                  repeated int32 values = 1 [packed = true];
                }
                """);
        byte[] protobuf = new byte[] {
                0x0a, 0x02, 0x01, 0x02
        };

        SchemaMessage schema = registry.message("demo.Numbers").orElseThrow();
        ProtobufCodec codec = new ProtobufCodec(registry);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);
        JsonNode values = decoded.get("f1");

        assertTrue(values.isArray());
        assertEquals(2, values.size());
        assertEquals(1, values.get(0).get("value").asInt());
        assertEquals(2, values.get(1).get("value").asInt());
        assertEquals(true, values.get(0).get("packed").asBoolean());
        assertArrayEquals(protobuf, codec.encodeMessage(decoded, schema));
    }

    @Test
    void decodesAndEncodesEnumSymbolicNames() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                enum Status {
                  UNKNOWN = 0;
                  ACTIVE = 1;
                }
                message Item {
                  Status status = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Item").orElseThrow();
        byte[] protobuf = new byte[] {0x08, 0x01};

        ProtobufCodec codec = new ProtobufCodec(registry);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);

        assertEquals("ACTIVE", decoded.get("f1").get("enumName").asText());
        assertArrayEquals(protobuf, codec.encodeMessage(decoded, schema));

        ((ObjectNode) decoded.get("f1")).put("enumName", "UNKNOWN");
        assertArrayEquals(new byte[] {0x08, 0x00}, codec.encodeMessage(decoded, schema));
    }

    @Test
    void marksFieldsBelongingToOneof() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Choice {
                  oneof kind {
                    string text = 1;
                    int32 number = 2;
                  }
                }
                """);
        SchemaMessage schema = registry.message("demo.Choice").orElseThrow();
        byte[] protobuf = new byte[] {0x0a, 0x02, 'o', 'k'};

        ObjectNode decoded = new ProtobufCodec(registry).decodeMessage(protobuf, schema);

        assertEquals("kind", decoded.get("f1").get("oneof").asText());
        assertEquals("text", decoded.get("f1").get("name").asText());
    }

    @Test
    void decodesAndEncodesMapFields() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Config {
                  map<string, string> labels = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Config").orElseThrow();
        byte[] entry = new byte[] {0x0a, 0x01, 'a', 0x12, 0x01, 'b'};
        byte[] protobuf = new byte[2 + entry.length];
        protobuf[0] = 0x0a;
        protobuf[1] = (byte) entry.length;
        System.arraycopy(entry, 0, protobuf, 2, entry.length);

        ProtobufCodec codec = new ProtobufCodec(registry);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);
        JsonNode field = decoded.get("f1");

        assertTrue(field.get("map").asBoolean());
        assertEquals("a", field.get("value").get("f1").get("value").asText());
        assertEquals("b", field.get("value").get("f2").get("value").asText());
        assertArrayEquals(protobuf, codec.encodeMessage(decoded, schema));
    }

    @Test
    void decodesGoogleProtobufAnyByTypeUrl() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Inner {
                  string text = 1;
                }
                message Wrapper {
                  google.protobuf.Any payload = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Wrapper").orElseThrow();

        byte[] innerBytes = new byte[] {0x0a, 0x02, 'h', 'i'};
        String typeUrl = "type.googleapis.com/demo.Inner";
        ByteArrayOutputStream anyBytes = new ByteArrayOutputStream();
        anyBytes.write(0x0a);
        anyBytes.write(typeUrl.length());
        anyBytes.writeBytes(typeUrl.getBytes(StandardCharsets.UTF_8));
        anyBytes.write(0x12);
        anyBytes.write(innerBytes.length);
        anyBytes.writeBytes(innerBytes);
        byte[] anyPayload = anyBytes.toByteArray();

        ByteArrayOutputStream wrapperBytes = new ByteArrayOutputStream();
        wrapperBytes.write(0x0a);
        wrapperBytes.write(anyPayload.length);
        wrapperBytes.writeBytes(anyPayload);

        ObjectNode decoded = new ProtobufCodec(registry).decodeMessage(wrapperBytes.toByteArray(), schema);
        JsonNode payload = decoded.get("f1");

        assertEquals("demo.Inner", payload.get("anyType").asText());
        assertEquals("hi", payload.get("anyValue").get("f1").get("value").asText());
    }

    @Test
    void addsReadableViewForWellKnownTimestampAndDuration() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Event {
                  google.protobuf.Timestamp occurred_at = 1;
                  google.protobuf.Duration elapsed = 2;
                }
                """);
        SchemaMessage schema = registry.message("demo.Event").orElseThrow();
        ObjectNode message = (ObjectNode) JSON.readTree("""
                {
                  "f1": {"type": "message", "value": {
                    "f1": {"type": "varint", "value": 1700000000},
                    "f2": {"type": "varint", "value": 0}
                  }},
                  "f2": {"type": "message", "value": {
                    "f1": {"type": "varint", "value": 5},
                    "f2": {"type": "varint", "value": 0}
                  }}
                }
                """);

        ProtobufCodec codec = new ProtobufCodec(registry);
        byte[] protobuf = codec.encodeMessage(message, schema);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);

        assertEquals(Instant.ofEpochSecond(1700000000, 0), Instant.parse(decoded.get("f1").get("readable").asText()));
        assertEquals("5.0s", decoded.get("f2").get("readable").asText());
    }

    @Test
    void encodesUsingSchemaFieldNamesAsAliases() throws Exception {
        SchemaField greetingField = new SchemaField(1, "greeting", "string", "", "", false, false);
        SchemaMessage schema = new SchemaMessage("demo.Hello",
                Map.of(1, greetingField),
                Map.of("greeting", greetingField));
        ObjectNode message = (ObjectNode) JSON.readTree("""
                {"greeting": {"type": "string", "value": "hi"}}
                """);

        byte[] protobuf = new ProtobufCodec().encodeMessage(message, schema);

        assertEquals("hi", new ProtobufCodec().decodeMessage(protobuf, schema).get("f1").get("value").asText());
    }

    @Test
    void enforcesConfigurableMaxRecursionDepth() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Node {
                  Node child = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Node").orElseThrow();
        byte[] level0 = new byte[0];
        byte[] level1 = wrapMessage(level0);
        byte[] level2 = wrapMessage(level1);

        assertDoesNotThrow(() -> new ProtobufCodec(registry).decodeMessage(level2, schema));
        assertThrows(IllegalArgumentException.class,
                () -> new ProtobufCodec(registry, 1).decodeMessage(level2, schema));
    }

    private static byte[] wrapMessage(byte[] inner) {
        byte[] out = new byte[2 + inner.length];
        out[0] = 0x0a;
        out[1] = (byte) inner.length;
        System.arraycopy(inner, 0, out, 2, inner.length);
        return out;
    }

    @Test
    void addsReadableViewForWellKnownWrapperTypes() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Wrapped {
                  google.protobuf.StringValue name = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Wrapped").orElseThrow();
        ObjectNode message = (ObjectNode) JSON.readTree("""
                {"f1": {"type": "message", "value": {"f1": {"type": "string", "value": "hi"}}}}
                """);

        ProtobufCodec codec = new ProtobufCodec(registry);
        byte[] protobuf = codec.encodeMessage(message, schema);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);

        assertEquals("hi", decoded.get("f1").get("readable").asText());
    }

    @Test
    void addsReadableViewForFieldMask() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Req {
                  google.protobuf.FieldMask update_mask = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Req").orElseThrow();
        ObjectNode message = (ObjectNode) JSON.readTree("""
                {"f1": {"type": "message", "value": {"f1": [
                    {"type": "string", "value": "name"},
                    {"type": "string", "value": "email"}
                ]}}}
                """);

        ProtobufCodec codec = new ProtobufCodec(registry);
        byte[] protobuf = codec.encodeMessage(message, schema);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);

        assertEquals("name,email", decoded.get("f1").get("readable").asText());
    }

    @Test
    void addsReadableJsonViewForStructValueAndListValue() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message Payload {
                  google.protobuf.Struct data = 1;
                }
                """);
        SchemaMessage schema = registry.message("demo.Payload").orElseThrow();

        ObjectNode message = (ObjectNode) JSON.readTree("""
                {
                  "f1": {"type": "message", "value": {
                    "f1": [
                      {"type": "message", "value": {
                        "f1": {"type": "string", "value": "name"},
                        "f2": {"type": "message", "value": {"f3": {"type": "string", "value": "ok"}}}
                      }},
                      {"type": "message", "value": {
                        "f1": {"type": "string", "value": "count"},
                        "f2": {"type": "message", "value": {"f2": {"type": "double", "value": 3}}}
                      }},
                      {"type": "message", "value": {
                        "f1": {"type": "string", "value": "flag"},
                        "f2": {"type": "message", "value": {"f4": {"type": "bool", "value": true}}}
                      }},
                      {"type": "message", "value": {
                        "f1": {"type": "string", "value": "tags"},
                        "f2": {"type": "message", "value": {"f6": {"type": "message", "value": {"f1": [
                          {"type": "message", "value": {"f3": {"type": "string", "value": "a"}}},
                          {"type": "message", "value": {"f3": {"type": "string", "value": "b"}}}
                        ]}}}}
                      }},
                      {"type": "message", "value": {
                        "f1": {"type": "string", "value": "meta"},
                        "f2": {"type": "message", "value": {"f5": {"type": "message", "value": {"f1": {
                          "type": "message", "value": {
                            "f1": {"type": "string", "value": "x"},
                            "f2": {"type": "message", "value": {"f4": {"type": "bool", "value": true}}}
                          }
                        }}}}}
                      }}
                    ]
                  }}
                }
                """);

        ProtobufCodec codec = new ProtobufCodec(registry);
        byte[] protobuf = codec.encodeMessage(message, schema);
        ObjectNode decoded = codec.decodeMessage(protobuf, schema);

        JsonNode readable = decoded.get("f1").get("readable");
        assertEquals("ok", readable.get("name").asText());
        assertEquals(3.0, readable.get("count").asDouble());
        assertTrue(readable.get("flag").asBoolean());
        assertEquals(2, readable.get("tags").size());
        assertEquals("a", readable.get("tags").get(0).asText());
        assertEquals("b", readable.get("tags").get(1).asText());
        assertTrue(readable.get("meta").get("x").asBoolean());
    }

    @Test
    void loadsBinaryDescriptorSetBytes() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();

        registry.addDescriptorSetBytes(sampleDescriptorSet().toByteArray());

        SchemaMessage schema = registry.message("demo.Greeting").orElseThrow();
        assertEquals("text", schema.field(1).name());
        assertEquals("string", schema.field(1).protoType());
    }

    @Test
    void loadsDescriptorSetFileByExtension() throws Exception {
        Path tempFile = Files.createTempFile("burp-grpc-codec-test", ".protoset");
        try {
            Files.write(tempFile, sampleDescriptorSet().toByteArray());

            SchemaRegistry registry = new SchemaRegistry();
            registry.loadProtoPaths(tempFile.toString());

            assertEquals("text", registry.message("demo.Greeting").orElseThrow().field(1).name());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static DescriptorProtos.FileDescriptorSet sampleDescriptorSet() {
        DescriptorProtos.FieldDescriptorProto field = DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName("text")
                .setNumber(1)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                .build();
        DescriptorProtos.DescriptorProto message = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Greeting")
                .addField(field)
                .build();
        DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("greeting.proto")
                .setPackage("demo")
                .setSyntax("proto3")
                .addMessageType(message)
                .build();
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build();
    }
}
