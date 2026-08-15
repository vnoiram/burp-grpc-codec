package com.github.burpgrpccodec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRegistryTest {
    @Test
    void allMessagesIncludesLoadedAndSeededWellKnownTypesSortedByName() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message HelloRequest {
                  string greeting = 1;
                }
                """);

        List<String> typeNames = registry.allMessages().stream().map(SchemaMessage::typeName).collect(Collectors.toList());

        assertTrue(typeNames.contains("demo.HelloRequest"));
        assertTrue(typeNames.contains("google.protobuf.Struct"));
        assertEquals(typeNames.stream().sorted().collect(Collectors.toList()), typeNames);
    }

    @Test
    void allMethodsReflectsLoadedServicesSortedByPath() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.addProtoSource("""
                syntax = "proto3";
                package demo;
                message HelloRequest {
                  string greeting = 1;
                }
                message HelloResponse {
                  string reply = 1;
                }
                service Greeter {
                  rpc SayHello (HelloRequest) returns (HelloResponse);
                  rpc SayBye (HelloRequest) returns (HelloResponse);
                }
                """);

        List<SchemaMethod> methods = registry.allMethods();

        assertEquals(2, methods.size());
        assertEquals("/demo.Greeter/SayBye", methods.get(0).path());
        assertEquals("/demo.Greeter/SayHello", methods.get(1).path());
    }
}
