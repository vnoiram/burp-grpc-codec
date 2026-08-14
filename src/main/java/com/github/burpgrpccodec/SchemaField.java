package com.github.burpgrpccodec;

record SchemaField(
        int number,
        String name,
        String protoType,
        String messageType,
        String enumType,
        boolean repeated,
        boolean packed,
        String oneof,
        boolean map
) {
    SchemaField(
            int number,
            String name,
            String protoType,
            String messageType,
            String enumType,
            boolean repeated,
            boolean packed
    ) {
        this(number, name, protoType, messageType, enumType, repeated, packed, "", false);
    }
}
