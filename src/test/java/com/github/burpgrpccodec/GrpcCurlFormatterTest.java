package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcCurlFormatterTest {
    @Test
    void simplifiesScalarAndNestedFields() {
        byte[] protobuf = new byte[] {
                0x0a, 0x05, 'h', 'e', 'l', 'l', 'o', // f1 string "hello"
                0x10, 0x7b                            // f2 varint 123
        };
        ObjectNode decoded = new ProtobufCodec().decodeMessage(protobuf);

        JsonNode simplified = GrpcCurlFormatter.simplify(decoded);

        assertEquals("hello", simplified.get("f1").asText());
        assertEquals(123, simplified.get("f2").asInt());
    }

    @Test
    void unwrapsNestedMessageFields() {
        // f2 (length-delimited) wraps a nested message whose f1 is varint 5.
        byte[] protobuf = new byte[] { 0x12, 0x02, 0x08, 0x05 };
        ObjectNode decoded = new ProtobufCodec().decodeMessage(protobuf);

        JsonNode simplified = GrpcCurlFormatter.simplify(decoded);

        assertEquals(5, simplified.get("f2").get("f1").asInt());
    }

    @Test
    void buildsPlaintextCommandForNonTlsTarget() {
        ObjectNode message = new ProtobufCodec().decodeMessage(new byte[] { 0x0a, 0x03, 'f', 'o', 'o' });

        String command = GrpcCurlFormatter.buildCommand("localhost:9000", false, "/demo.Greeter/SayHello", message);

        assertTrue(command.startsWith("grpcurl -plaintext "));
        assertTrue(command.contains("localhost:9000 demo.Greeter/SayHello"));
        assertTrue(command.contains("\"f1\":\"foo\""));
    }

    @Test
    void omitsPlaintextFlagForTlsTarget() {
        ObjectNode message = new ProtobufCodec().decodeMessage(new byte[0]);

        String command = GrpcCurlFormatter.buildCommand("api.example.com:443", true, "demo.Greeter/SayHello", message);

        assertTrue(command.startsWith("grpcurl -d "));
        assertTrue(command.contains("api.example.com:443 demo.Greeter/SayHello"));
    }
}
