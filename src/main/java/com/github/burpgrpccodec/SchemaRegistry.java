package com.github.burpgrpccodec;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SchemaRegistry {
    private static final String WELL_KNOWN_PROTO = """
            syntax = "proto3";
            package google.protobuf;
            message Struct {
              map<string, Value> fields = 1;
            }
            message Value {
              oneof kind {
                NullValue null_value = 1;
                double number_value = 2;
                string string_value = 3;
                bool bool_value = 4;
                Struct struct_value = 5;
                ListValue list_value = 6;
              }
            }
            message ListValue {
              repeated Value values = 1;
            }
            enum NullValue {
              NULL_VALUE = 0;
            }
            """;

    private final Map<String, SchemaMessage> messages = new HashMap<>();
    private final Map<String, SchemaMethod> methods = new HashMap<>();
    private final Map<String, SchemaEnum> enums = new HashMap<>();

    SchemaRegistry() {
        seedWellKnownTypes();
    }

    Optional<SchemaMessage> message(String typeName) {
        String normalized = normalizeType(typeName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(messages.get(normalized));
    }

    Optional<SchemaMessage> messageForPath(String path, boolean response) {
        String normalized = normalizePath(path);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        SchemaMethod method = methods.get(normalized);
        if (method == null) {
            return Optional.empty();
        }
        return message(response ? method.responseType() : method.requestType());
    }

    Optional<String> enumName(String enumType, long value) {
        SchemaEnum schemaEnum = enums.get(normalizeType(enumType));
        if (schemaEnum == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(schemaEnum.nameOf(value));
    }

    OptionalLong enumValue(String enumType, String name) {
        SchemaEnum schemaEnum = enums.get(normalizeType(enumType));
        if (schemaEnum == null) {
            return OptionalLong.empty();
        }
        Integer number = schemaEnum.numberOf(name);
        return number == null ? OptionalLong.empty() : OptionalLong.of(number);
    }

    int messageCount() {
        return messages.size();
    }

    int methodCount() {
        return methods.size();
    }

    void reload(ExtensionSettings settings) {
        messages.clear();
        methods.clear();
        enums.clear();
        seedWellKnownTypes();
        loadProtoPaths(settings.protoPaths());
        String target = settings.reflectionTarget();
        if (!target.isBlank()) {
            Optional<DescriptorProtos.FileDescriptorSet> reflected = new ReflectionSchemaLoader()
                    .load(target, settings.reflectionTls(), settings.reflectionTimeoutSeconds());
            reflected.ifPresent(this::addDescriptor);
        }
    }

    void addProtoSource(String source) {
        ProtoFile parsed = ProtoFile.parse(source);
        Set<String> enumNames = parsed.enumNames();
        for (ProtoEnum protoEnum : parsed.enums()) {
            registerEnum(parsed.packageName(), protoEnum);
        }
        for (ProtoMessage message : parsed.messages()) {
            addMessage(parsed.packageName(), enumNames, message);
        }
        for (ProtoService service : parsed.services()) {
            addService(parsed.packageName(), service);
        }
    }

    void addDescriptor(DescriptorProtos.FileDescriptorSet set) {
        Map<String, Descriptors.FileDescriptor> built = new LinkedHashMap<>();
        Set<String> failed = new HashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (DescriptorProtos.FileDescriptorProto proto : set.getFileList()) {
                if (built.containsKey(proto.getName()) || failed.contains(proto.getName())) {
                    continue;
                }
                List<Descriptors.FileDescriptor> deps = new ArrayList<>();
                boolean ready = true;
                for (String depName : proto.getDependencyList()) {
                    Descriptors.FileDescriptor dep = built.get(depName);
                    if (dep == null) {
                        ready = false;
                        break;
                    }
                    deps.add(dep);
                }
                if (ready) {
                    try {
                        Descriptors.FileDescriptor file = Descriptors.FileDescriptor.buildFrom(
                                proto, deps.toArray(Descriptors.FileDescriptor[]::new));
                        built.put(proto.getName(), file);
                        addFileDescriptor(file);
                        progressed = true;
                    } catch (Descriptors.DescriptorValidationException ignored) {
                        failed.add(proto.getName());
                    }
                }
            }
        } while (progressed);
    }

    private void seedWellKnownTypes() {
        addProtoSource(WELL_KNOWN_PROTO);
    }

    void loadProtoPaths(String protoPaths) {
        for (String part : protoPaths.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed);
            try {
                if (Files.isDirectory(path)) {
                    try (Stream<Path> files = Files.walk(path)) {
                        files.filter(file -> hasSupportedExtension(file.toString()))
                                .forEach(this::loadProtoFile);
                    }
                } else {
                    loadProtoFile(path);
                }
            } catch (IOException ignored) {
                // Invalid user-provided schema paths should not break message editing.
            }
        }
    }

    private static boolean hasSupportedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".proto") || isDescriptorSetExtension(lower);
    }

    private static boolean isDescriptorSetExtension(String lowerCaseFileName) {
        return lowerCaseFileName.endsWith(".protoset")
                || lowerCaseFileName.endsWith(".pb")
                || lowerCaseFileName.endsWith(".desc")
                || lowerCaseFileName.endsWith(".bin");
    }

    private void loadProtoFile(Path path) {
        if (isDescriptorSetExtension(path.getFileName().toString().toLowerCase(Locale.ROOT))) {
            loadDescriptorSetFile(path);
            return;
        }
        try {
            addProtoSource(Files.readString(path));
        } catch (IOException ignored) {
            // Invalid user-provided schema paths should not break message editing.
        }
    }

    private void loadDescriptorSetFile(Path path) {
        try {
            addDescriptorSetBytes(Files.readAllBytes(path));
        } catch (IOException ignored) {
            // Invalid user-provided schema paths should not break message editing.
        }
    }

    void addDescriptorSetBytes(byte[] bytes) throws InvalidProtocolBufferException {
        addDescriptor(DescriptorProtos.FileDescriptorSet.parseFrom(bytes));
    }

    private void addFileDescriptor(Descriptors.FileDescriptor file) {
        for (Descriptors.EnumDescriptor enumType : file.getEnumTypes()) {
            addDescriptorEnum(enumType);
        }
        for (Descriptors.Descriptor descriptor : file.getMessageTypes()) {
            addDescriptorMessage(descriptor);
        }
        for (Descriptors.ServiceDescriptor service : file.getServices()) {
            addDescriptorService(service);
        }
    }

    private void addDescriptorEnum(Descriptors.EnumDescriptor descriptor) {
        Map<Integer, String> byNumber = new LinkedHashMap<>();
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (Descriptors.EnumValueDescriptor value : descriptor.getValues()) {
            byNumber.putIfAbsent(value.getNumber(), value.getName());
            byName.put(value.getName(), value.getNumber());
        }
        enums.put(descriptor.getFullName(), new SchemaEnum(descriptor.getFullName(), Map.copyOf(byNumber), Map.copyOf(byName)));
    }

    private void addDescriptorMessage(Descriptors.Descriptor descriptor) {
        Map<Integer, SchemaField> byNumber = new LinkedHashMap<>();
        Map<String, SchemaField> byName = new LinkedHashMap<>();
        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            String messageType = field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE
                    ? field.getMessageType().getFullName()
                    : "";
            String enumType = field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM
                    ? field.getEnumType().getFullName()
                    : "";
            Descriptors.OneofDescriptor oneof = field.getRealContainingOneof();
            SchemaField schemaField = new SchemaField(
                    field.getNumber(),
                    field.getName(),
                    field.getType().name().toLowerCase(Locale.ROOT),
                    messageType,
                    enumType,
                    field.isRepeated(),
                    field.isPacked(),
                    oneof == null ? "" : oneof.getName(),
                    field.isMapField());
            byNumber.put(schemaField.number(), schemaField);
            byName.put(schemaField.name(), schemaField);
        }
        SchemaMessage message = new SchemaMessage(descriptor.getFullName(), Map.copyOf(byNumber), Map.copyOf(byName));
        messages.put(message.typeName(), message);
        for (Descriptors.EnumDescriptor nestedEnum : descriptor.getEnumTypes()) {
            addDescriptorEnum(nestedEnum);
        }
        for (Descriptors.Descriptor nested : descriptor.getNestedTypes()) {
            addDescriptorMessage(nested);
        }
    }

    private void addDescriptorService(Descriptors.ServiceDescriptor service) {
        for (Descriptors.MethodDescriptor method : service.getMethods()) {
            String path = "/" + service.getFullName() + "/" + method.getName();
            methods.put(path, new SchemaMethod(path, method.getInputType().getFullName(), method.getOutputType().getFullName()));
        }
    }

    private void registerEnum(String packageName, ProtoEnum protoEnum) {
        String qualified = packageName.isBlank() ? protoEnum.name() : packageName + "." + protoEnum.name();
        Map<String, Integer> byName = new LinkedHashMap<>();
        protoEnum.values().forEach((number, name) -> byName.putIfAbsent(name, number));
        enums.put(qualified, new SchemaEnum(qualified, Map.copyOf(protoEnum.values()), Map.copyOf(byName)));
    }

    private void addMessage(String packageName, Set<String> enumNames, ProtoMessage protoMessage) {
        String typeName = packageName.isBlank() ? protoMessage.name() : packageName + "." + protoMessage.name();
        Map<Integer, SchemaField> byNumber = new LinkedHashMap<>();
        Map<String, SchemaField> byName = new LinkedHashMap<>();
        for (ProtoField field : protoMessage.fields()) {
            SchemaField schemaField = field.map()
                    ? mapSchemaField(packageName, enumNames, typeName, field)
                    : scalarOrMessageSchemaField(packageName, enumNames, field);
            byNumber.put(schemaField.number(), schemaField);
            byName.put(schemaField.name(), schemaField);
        }
        messages.put(typeName, new SchemaMessage(typeName, Map.copyOf(byNumber), Map.copyOf(byName)));
    }

    private SchemaField scalarOrMessageSchemaField(String packageName, Set<String> enumNames, ProtoField field) {
        String normalizedType = normalizeProtoType(packageName, field.type());
        boolean enumField = enumNames.contains(field.type()) || enumNames.contains(normalizedType);
        boolean message = !isScalar(field.type()) && !enumField;
        return new SchemaField(
                field.number(),
                field.name(),
                field.type(),
                message ? normalizedType : "",
                enumField ? normalizedType : "",
                field.repeated(),
                field.packed(),
                field.oneof(),
                false);
    }

    private SchemaField mapSchemaField(String packageName, Set<String> enumNames, String containingType, ProtoField field) {
        String entryTypeName = containingType + "." + toPascalCase(field.name()) + "Entry";
        registerMapEntry(packageName, enumNames, entryTypeName, field.mapKeyType(), field.mapValueType());
        return new SchemaField(
                field.number(),
                field.name(),
                "message",
                entryTypeName,
                "",
                true,
                false,
                field.oneof(),
                true);
    }

    private void registerMapEntry(String packageName, Set<String> enumNames, String entryTypeName, String keyType, String valueType) {
        String normalizedValueType = normalizeProtoType(packageName, valueType);
        boolean valueEnum = enumNames.contains(valueType) || enumNames.contains(normalizedValueType);
        boolean valueMessage = !isScalar(valueType) && !valueEnum;
        SchemaField keyField = new SchemaField(1, "key", keyType, "", "", false, false, "", false);
        SchemaField valueField = new SchemaField(
                2, "value", valueType,
                valueMessage ? normalizedValueType : "",
                valueEnum ? normalizedValueType : "",
                false, false, "", false);
        messages.put(entryTypeName, new SchemaMessage(entryTypeName, Map.of(1, keyField, 2, valueField), Map.of("key", keyField, "value", valueField)));
    }

    private void addService(String packageName, ProtoService protoService) {
        String serviceName = packageName.isBlank() ? protoService.name() : packageName + "." + protoService.name();
        for (ProtoRpc rpc : protoService.rpcs()) {
            String requestType = normalizeProtoType(packageName, rpc.requestType());
            String responseType = normalizeProtoType(packageName, rpc.responseType());
            String path = "/" + serviceName + "/" + rpc.name();
            methods.put(path, new SchemaMethod(path, requestType, responseType));
        }
    }

    private static String normalizeProtoType(String packageName, String type) {
        if (type.startsWith(".")) {
            return type.substring(1);
        }
        if (isScalar(type) || packageName.isBlank() || type.contains(".")) {
            return type;
        }
        return packageName + "." + type;
    }

    private static String normalizeType(String typeName) {
        String value = typeName == null ? "" : typeName.trim();
        return value.startsWith(".") ? value.substring(1) : value;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int query = path.indexOf('?');
        String withoutQuery = query >= 0 ? path.substring(0, query) : path;
        return withoutQuery.startsWith("/") ? withoutQuery : "/" + withoutQuery;
    }

    private static boolean isScalar(String type) {
        return switch (type) {
            case "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
                    "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes" -> true;
            default -> false;
        };
    }

    private static String toPascalCase(String snakeCase) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = true;
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                upperNext = true;
                continue;
            }
            result.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return result.toString();
    }

    private static String blank(String text, int start, int end) {
        return text.substring(0, start) + " ".repeat(end - start) + text.substring(end);
    }

    private record ProtoFile(String packageName, List<ProtoEnum> enums, List<ProtoMessage> messages, List<ProtoService> services) {
        private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");
        private static final Pattern ENUM = Pattern.compile("\\benum\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");
        private static final Pattern ENUM_VALUE = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(-?\\d+)");
        private static final Pattern MESSAGE = Pattern.compile("\\bmessage\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");
        private static final Pattern SERVICE = Pattern.compile("\\bservice\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");
        private static final Pattern RPC = Pattern.compile(
                "\\brpc\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)\\s*returns\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");
        private static final Pattern ONEOF = Pattern.compile("\\boneof\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");
        private static final Pattern MAP_FIELD = Pattern.compile(
                "\\bmap\\s*<\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*>\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\\d+)[^;]*;");
        private static final Pattern FIELD = Pattern.compile(
                "\\b(optional|required|repeated)?\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\\d+)([^;]*);");

        static ProtoFile parse(String source) {
            String stripped = stripComments(source);
            Matcher packageMatcher = PACKAGE.matcher(stripped);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
            return new ProtoFile(packageName, parseEnums(stripped), parseMessages(stripped), parseServices(stripped));
        }

        Set<String> enumNames() {
            Set<String> names = new HashSet<>();
            for (ProtoEnum protoEnum : enums) {
                names.add(protoEnum.name());
                if (!packageName.isBlank()) {
                    names.add(packageName + "." + protoEnum.name());
                }
            }
            return names;
        }

        private static List<ProtoEnum> parseEnums(String source) {
            List<ProtoEnum> enums = new ArrayList<>();
            Matcher matcher = ENUM.matcher(source);
            while (matcher.find()) {
                int bodyStart = matcher.end();
                int bodyEnd = findBlockEnd(source, bodyStart - 1);
                if (bodyEnd > bodyStart) {
                    enums.add(new ProtoEnum(matcher.group(1), parseEnumValues(source.substring(bodyStart, bodyEnd))));
                }
            }
            return enums;
        }

        private static Map<Integer, String> parseEnumValues(String body) {
            Map<Integer, String> values = new LinkedHashMap<>();
            Matcher matcher = ENUM_VALUE.matcher(body);
            while (matcher.find()) {
                values.putIfAbsent(Integer.parseInt(matcher.group(2)), matcher.group(1));
            }
            return values;
        }

        private static List<ProtoMessage> parseMessages(String source) {
            List<ProtoMessage> messages = new ArrayList<>();
            Matcher matcher = MESSAGE.matcher(source);
            while (matcher.find()) {
                int bodyStart = matcher.end();
                int bodyEnd = findBlockEnd(source, bodyStart - 1);
                if (bodyEnd > bodyStart) {
                    messages.add(new ProtoMessage(matcher.group(1), parseFields(source.substring(bodyStart, bodyEnd))));
                }
            }
            return messages;
        }

        private static List<ProtoService> parseServices(String source) {
            List<ProtoService> services = new ArrayList<>();
            Matcher matcher = SERVICE.matcher(source);
            while (matcher.find()) {
                int bodyStart = matcher.end();
                int bodyEnd = findBlockEnd(source, bodyStart - 1);
                if (bodyEnd > bodyStart) {
                    services.add(new ProtoService(matcher.group(1), parseRpcs(source.substring(bodyStart, bodyEnd))));
                }
            }
            return services;
        }

        private static List<ProtoRpc> parseRpcs(String body) {
            List<ProtoRpc> rpcs = new ArrayList<>();
            Matcher matcher = RPC.matcher(body);
            while (matcher.find()) {
                rpcs.add(new ProtoRpc(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
            return rpcs;
        }

        private static List<ProtoField> parseFields(String body) {
            List<ProtoField> fields = new ArrayList<>();
            String working = body;

            while (true) {
                Matcher matcher = ONEOF.matcher(working);
                if (!matcher.find()) {
                    break;
                }
                int innerStart = matcher.end();
                int innerEnd = findBlockEnd(working, innerStart - 1);
                if (innerEnd <= innerStart) {
                    working = blank(working, matcher.start(), matcher.end());
                    continue;
                }
                fields.addAll(parsePlainFields(working.substring(innerStart, innerEnd), matcher.group(1)));
                working = blank(working, matcher.start(), innerEnd + 1);
            }

            while (true) {
                Matcher matcher = MAP_FIELD.matcher(working);
                if (!matcher.find()) {
                    break;
                }
                fields.add(ProtoField.mapField(matcher.group(3), Integer.parseInt(matcher.group(4)), matcher.group(1), matcher.group(2)));
                working = blank(working, matcher.start(), matcher.end());
            }

            fields.addAll(parsePlainFields(working, ""));
            return fields;
        }

        private static List<ProtoField> parsePlainFields(String body, String oneofName) {
            List<ProtoField> fields = new ArrayList<>();
            Matcher matcher = FIELD.matcher(body);
            while (matcher.find()) {
                boolean repeated = "repeated".equals(matcher.group(1));
                boolean packed = matcher.group(5).contains("packed") && matcher.group(5).contains("true");
                fields.add(new ProtoField(matcher.group(3), matcher.group(2), Integer.parseInt(matcher.group(4)), repeated, packed, oneofName));
            }
            return fields;
        }

        private static int findBlockEnd(String source, int openBrace) {
            ArrayDeque<Character> stack = new ArrayDeque<>();
            for (int i = openBrace; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    stack.push(c);
                } else if (c == '}') {
                    stack.pop();
                    if (stack.isEmpty()) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static String stripComments(String source) {
            return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        }
    }

    private record ProtoEnum(String name, Map<Integer, String> values) {
    }

    private record ProtoMessage(String name, List<ProtoField> fields) {
    }

    private record ProtoField(
            String name,
            String type,
            int number,
            boolean repeated,
            boolean packed,
            String oneof,
            boolean map,
            String mapKeyType,
            String mapValueType
    ) {
        private ProtoField(String name, String type, int number, boolean repeated, boolean packed, String oneof) {
            this(name, type, number, repeated, packed, oneof, false, "", "");
        }

        static ProtoField mapField(String name, int number, String keyType, String valueType) {
            return new ProtoField(name, "", number, false, false, "", true, keyType, valueType);
        }
    }

    private record ProtoService(String name, List<ProtoRpc> rpcs) {
    }

    private record ProtoRpc(String name, String requestType, String responseType) {
    }
}
