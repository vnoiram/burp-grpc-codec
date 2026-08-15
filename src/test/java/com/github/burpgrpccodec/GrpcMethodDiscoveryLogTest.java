package com.github.burpgrpccodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void entryLineFormatRoundTripsThroughParse() {
        // GrpcMethodDiscoveryLog persists entries as "host\tpath\tcount" lines
        // via the Montoya PersistedObject API (not exercisable here without a
        // running Burp instance); this pins the line format that persist()
        // writes and load() reads back.
        GrpcMethodDiscoveryLog.Entry original = new GrpcMethodDiscoveryLog.Entry("api.example.com", "/demo.Greeter/SayHello", 3);

        GrpcMethodDiscoveryLog.Entry parsed = GrpcMethodDiscoveryLog.Entry.parse(
                original.host() + "\t" + original.path() + "\t" + original.count());

        assertEquals(original, parsed);
        assertNull(GrpcMethodDiscoveryLog.Entry.parse("malformed-line"));
    }

    @Test
    void rendersEntriesAsCsvWithQuotingForSpecialCharacters() {
        GrpcMethodDiscoveryLog log = new GrpcMethodDiscoveryLog();

        log.record("api.example.com", "/demo.Greeter/SayHello");
        log.record("weird,host\"name", "/demo.Greeter/SayGoodbye");

        String csv = GrpcMethodDiscoveryLog.toCsv(log.entries());

        assertTrue(csv.startsWith("host,path,count\n"));
        assertTrue(csv.contains("api.example.com,/demo.Greeter/SayHello,1\n"));
        assertTrue(csv.contains("\"weird,host\"\"name\",/demo.Greeter/SayGoodbye,1\n"));
    }

    @Test
    void rendersEntriesAsJsonArray() throws Exception {
        GrpcMethodDiscoveryLog log = new GrpcMethodDiscoveryLog();
        log.record("api.example.com", "/demo.Greeter/SayHello");
        log.record("api.example.com", "/demo.Greeter/SayHello");

        String json = GrpcMethodDiscoveryLog.toJson(log.entries());
        JsonNode array = new ObjectMapper().readTree(json);

        assertEquals(1, array.size());
        assertEquals("api.example.com", array.get(0).get("host").asText());
        assertEquals("/demo.Greeter/SayHello", array.get(0).get("path").asText());
        assertEquals(2, array.get(0).get("count").asInt());
    }
}
