package com.github.burpgrpccodec;

import java.util.Map;

record SchemaEnum(String typeName, Map<Integer, String> valuesByNumber, Map<String, Integer> valuesByName) {
    String nameOf(long number) {
        return valuesByNumber.get((int) number);
    }

    Integer numberOf(String name) {
        return valuesByName.get(name);
    }
}
