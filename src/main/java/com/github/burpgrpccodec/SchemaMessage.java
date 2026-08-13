package com.github.burpgrpccodec;

import java.util.Map;

record SchemaMessage(String typeName, Map<Integer, SchemaField> fieldsByNumber, Map<String, SchemaField> fieldsByName) {
    SchemaField field(int number) {
        return fieldsByNumber.get(number);
    }
}
