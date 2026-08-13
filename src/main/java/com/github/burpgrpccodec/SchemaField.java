package com.github.burpgrpccodec;

record SchemaField(
        int number,
        String name,
        String protoType,
        String messageType,
        String enumType,
        boolean repeated,
        boolean packed
) {
}
