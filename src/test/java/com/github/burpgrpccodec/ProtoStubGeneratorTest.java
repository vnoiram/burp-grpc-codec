package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoStubGeneratorTest {
    @Test
    void generatesStubForScalarFields() {
        byte[] protobuf = new byte[] {
                0x0a, 0x05, 'h', 'e', 'l', 'l', 'o', // f1 string "hello"
                0x10, 0x7b                            // f2 varint 123
        };
        ObjectNode decoded = new ProtobufCodec().decodeMessage(protobuf);

        String stub = ProtoStubGenerator.generate("Root", decoded);

        assertTrue(stub.contains("syntax = \"proto3\";"));
        assertTrue(stub.contains("message Root {"));
        assertTrue(stub.contains("string f1 = 1;"));
        assertTrue(stub.contains("int64 f2 = 2;"));
    }

    @Test
    void generatesSeparateMessageForNestedProtobuf() {
        // f2 (length-delimited) wraps a nested message whose f1 is varint 5.
        byte[] protobuf = new byte[] { 0x12, 0x02, 0x08, 0x05 };
        ObjectNode decoded = new ProtobufCodec().decodeMessage(protobuf);

        String stub = ProtoStubGenerator.generate("Root", decoded);

        assertTrue(stub.contains("message Root {"));
        assertTrue(stub.contains(" f2 = 2;"));
        assertTrue(stub.contains("message Root_Nested1 {"));
        assertTrue(stub.contains("int64 f1 = 1;"));
    }

    @Test
    void usesSchemaFieldNamesAndTypesWhenAvailable() {
        SchemaMessage schema = new SchemaMessage(
                "demo.Greeting",
                java.util.Map.of(1, new SchemaField(1, "greeting", "string", "", "", false, false)),
                java.util.Map.of());
        ObjectNode decoded = new ProtobufCodec(new SchemaRegistry())
                .decodeMessage(new byte[] { 0x0a, 0x05, 'h', 'e', 'l', 'l', 'o' }, schema);

        String stub = ProtoStubGenerator.generate("Greeting", decoded);

        assertTrue(stub.contains("string greeting = 1;"));
    }
}
