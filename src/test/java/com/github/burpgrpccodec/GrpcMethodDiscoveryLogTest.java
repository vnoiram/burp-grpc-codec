package com.github.burpgrpccodec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcMethodDiscoveryLogTest {
    @Test
    void deduplicatesAndCountsRepeatedObservations() {
        GrpcMethodDiscoveryLog log = new GrpcMethodDiscoveryLog();

        log.record("api.example.com", "/demo.Greeter/SayHello");
        log.record("api.example.com", "/demo.Greeter/SayHello");
        log.record("api.example.com", "/demo.Greeter/SayGoodbye");

        assertEquals(2, log.size());
        GrpcMethodDiscoveryLog.Entry sayHello = log.entries().stream()
                .filter(entry -> entry.path().equals("/demo.Greeter/SayHello"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, sayHello.count());
    }

    @Test
    void distinguishesSamePathOnDifferentHosts() {
        GrpcMethodDiscoveryLog log = new GrpcMethodDiscoveryLog();

        log.record("a.example.com", "/demo.Greeter/SayHello");
        log.record("b.example.com", "/demo.Greeter/SayHello");

        assertEquals(2, log.size());
    }

    @Test
    void ignoresBlankPaths() {
        GrpcMethodDiscoveryLog log = new GrpcMethodDiscoveryLog();

        log.record("api.example.com", "");
        log.record("api.example.com", null);

        assertEquals(0, log.size());
    }

    @Test
    void entriesAreSortedByHostThenPath() {
        GrpcMethodDiscoveryLog log = new GrpcMethodDiscoveryLog();

        log.record("b.example.com", "/demo.Greeter/SayHello");
        log.record("a.example.com", "/demo.Greeter/SayGoodbye");
        log.record("a.example.com", "/demo.Greeter/SayHello");

        List<GrpcMethodDiscoveryLog.Entry> entries = log.entries();

        assertEquals("a.example.com", entries.get(0).host());
        assertEquals("/demo.Greeter/SayGoodbye", entries.get(0).path());
        assertEquals("a.example.com", entries.get(1).host());
        assertEquals("/demo.Greeter/SayHello", entries.get(1).path());
        assertEquals("b.example.com", entries.get(2).host());

        log.clear();
        assertTrue(log.entries().isEmpty());
    }
}
