package com.github.burpgrpccodec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaProtoExporterTest {
    @Test
    void rendersMessagesAndGroupedServiceMethods() {
        SchemaField greeting = new SchemaField(1, "greeting", "string", "", "", false, false);
        SchemaMessage request = new SchemaMessage("demo.HelloRequest", Map.of(1, greeting), Map.of("greeting", greeting));
        SchemaField replyText = new SchemaField(1, "message", "string", "", "", false, false);
        SchemaMessage response = new SchemaMessage("demo.HelloResponse", Map.of(1, replyText), Map.of("message", replyText));
        SchemaMethod sayHello = new SchemaMethod("/demo.Greeter/SayHello", "demo.HelloRequest", "demo.HelloResponse");
        SchemaMethod sayBye = new SchemaMethod("/demo.Greeter/SayBye", "demo.HelloRequest", "demo.HelloResponse");

        String proto = SchemaProtoExporter.export(List.of(request, response), List.of(sayHello, sayBye));

        assertTrue(proto.startsWith("syntax = \"proto3\";"));
        assertTrue(proto.contains("message HelloRequest {"));
        assertTrue(proto.contains("string greeting = 1;"));
        assertTrue(proto.contains("service Greeter {"));
        assertTrue(proto.contains("rpc SayHello (.demo.HelloRequest) returns (.demo.HelloResponse);"));
        assertTrue(proto.contains("rpc SayBye (.demo.HelloRequest) returns (.demo.HelloResponse);"));
        // Both methods belong to the same service and should be grouped into one block.
        assertTrue(proto.indexOf("service Greeter {") == proto.lastIndexOf("service Greeter {"));
    }

    @Test
    void referencesNestedMessageAndEnumFieldsFullyQualified() {
        SchemaField nested = new SchemaField(1, "address", "message", "demo.Address", "", false, false);
        SchemaField status = new SchemaField(2, "status", "enum", "", "demo.Status", true, false);
        SchemaMessage message = new SchemaMessage("demo.Profile",
                Map.of(1, nested, 2, status), Map.of("address", nested, "status", status));

        String proto = SchemaProtoExporter.export(List.of(message), List.of());

        assertTrue(proto.contains(".demo.Address address = 1;"));
        assertTrue(proto.contains("repeated .demo.Status status = 2;"));
    }

    @Test
    void skipsSeededWellKnownGoogleProtobufMessages() {
        SchemaField value = new SchemaField(1, "value", "string", "", "", false, false);
        SchemaMessage wellKnown = new SchemaMessage("google.protobuf.StringValue", Map.of(1, value), Map.of("value", value));

        String proto = SchemaProtoExporter.export(List.of(wellKnown), List.of());

        assertFalse(proto.contains("message StringValue"));
    }
}
