package com.github.burpgrpccodec;

import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe, in-memory record of distinct (host, gRPC path) pairs observed
 * on outgoing requests, used to give a passive inventory of discovered
 * service/method endpoints without needing Server Reflection.
 */
final class GrpcMethodDiscoveryLog {
    private static final String PERSISTENCE_KEY = "discoveredGrpcMethods";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Entry> entriesByKey = new ConcurrentHashMap<>();
    private final PersistedObject persistence;

    GrpcMethodDiscoveryLog() {
        this(null);
    }

    GrpcMethodDiscoveryLog(PersistedObject persistence) {
        this.persistence = persistence;
        if (persistence != null) {
            PersistedList<String> saved = persistence.getStringList(PERSISTENCE_KEY);
            if (saved != null) {
                for (String line : saved) {
                    Entry entry = Entry.parse(line);
                    if (entry != null) {
                        entriesByKey.put(entry.host() + " " + entry.path(), entry);
                    }
                }
            }
        }
    }

    void record(String host, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String safeHost = host == null ? "" : host;
        String key = safeHost + " " + path;
        boolean[] isNewEntry = new boolean[1];
        entriesByKey.compute(key, (k, existing) -> {
            isNewEntry[0] = existing == null;
            return existing == null ? new Entry(safeHost, path, 1) : new Entry(safeHost, path, existing.count() + 1);
        });
        // Persist only when a new distinct method is seen, not on every
        // repeated observation, so high-volume traffic doesn't churn the
        // extension data store on every request.
        if (isNewEntry[0]) {
            persist();
        }
    }

    List<Entry> entries() {
        return entriesByKey.values().stream()
                .sorted(Comparator.comparing(Entry::host).thenComparing(Entry::path))
                .collect(Collectors.toList());
    }

    int size() {
        return entriesByKey.size();
    }

    void clear() {
        entriesByKey.clear();
        persist();
    }

    /**
     * Renders the given entries as CSV (host,path,count), quoting fields that
     * contain a comma, quote, or newline per RFC 4180.
     */
    static String toCsv(List<Entry> entries) {
        StringBuilder csv = new StringBuilder("host,path,count\n");
        for (Entry entry : entries) {
            csv.append(csvField(entry.host())).append(',')
                    .append(csvField(entry.path())).append(',')
                    .append(entry.count()).append('\n');
        }
        return csv.toString();
    }

    /** Renders the given entries as a JSON array of {@code {host, path, count}} objects. */
    static String toJson(List<Entry> entries) {
        ArrayNode array = JSON.createArrayNode();
        for (Entry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("host", entry.host());
            node.put("path", entry.path());
            node.put("count", entry.count());
        }
        try {
            return JSON.writeValueAsString(array);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void persist() {
        if (persistence == null) {
            return;
        }
        PersistedList<String> saved = PersistedList.persistedStringList();
        for (Entry entry : entries()) {
            saved.add(entry.host() + "\t" + entry.path() + "\t" + entry.count());
        }
        persistence.setStringList(PERSISTENCE_KEY, saved);
    }

    record Entry(String host, String path, int count) {
        static Entry parse(String line) {
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) {
                return null;
            }
            try {
                return new Entry(parts[0], parts[1], Integer.parseInt(parts[2]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
