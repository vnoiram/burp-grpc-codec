package com.github.burpgrpccodec;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SchemaRegistry {
    private final Map<String, SchemaMessage> messages = new HashMap<>();

    Optional<SchemaMessage> message(String typeName) {
        String normalized = normalizeType(typeName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(messages.get(normalized));
    }

    void reload(ExtensionSettings settings) {
        messages.clear();
        loadProtoPaths(settings.protoPaths());
        String target = settings.reflectionTarget();
        if (!target.isBlank()) {
            Optional<DescriptorProtos.FileDescriptorSet> reflected = new ReflectionSchemaLoader().load(target, settings.reflectionTls());
            if (reflected.isPresent()) {
                addDescriptor(reflected.get());
            }
        }
    }

    void addProtoSource(String source) {
        ProtoFile parsed = ProtoFile.parse(source);
        for (ProtoMessage message : parsed.messages) {
            addMessage(parsed.packageName, parsed.enums, message);
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

    private void loadProtoPaths(String protoPaths) {
        for (String part : protoPaths.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed);
            try {
                if (Files.isDirectory(path)) {
                    try (Stream<Path> files = Files.walk(path)) {
                        files.filter(file -> file.toString().endsWith(".proto"))
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

    private void loadProtoFile(Path path) {
        try {
            addProtoSource(Files.readString(path));
        } catch (IOException ignored) {
            // Invalid user-provided schema paths should not break message editing.
        }
    }

    private void addFileDescriptor(Descriptors.FileDescriptor file) {
        for (Descriptors.Descriptor descriptor : file.getMessageTypes()) {
            addDescriptorMessage(descriptor);
        }
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
            SchemaField schemaField = new SchemaField(
                    field.getNumber(),
                    field.getName(),
                    field.getType().name().toLowerCase(Locale.ROOT),
                    messageType,
                    enumType,
                    field.isRepeated(),
                    field.isPacked());
            byNumber.put(schemaField.number(), schemaField);
            byName.put(schemaField.name(), schemaField);
        }
        SchemaMessage message = new SchemaMessage(descriptor.getFullName(), Map.copyOf(byNumber), Map.copyOf(byName));
        messages.put(message.typeName(), message);
        for (Descriptors.Descriptor nested : descriptor.getNestedTypes()) {
            addDescriptorMessage(nested);
        }
    }

    private void addMessage(String packageName, Set<String> enumNames, ProtoMessage protoMessage) {
        String typeName = packageName.isBlank() ? protoMessage.name : packageName + "." + protoMessage.name;
        Map<Integer, SchemaField> byNumber = new LinkedHashMap<>();
        Map<String, SchemaField> byName = new LinkedHashMap<>();
        for (ProtoField field : protoMessage.fields) {
            String normalizedType = normalizeProtoType(packageName, field.type);
            boolean enumField = enumNames.contains(field.type) || enumNames.contains(normalizedType);
            boolean message = !isScalar(field.type) && !enumField;
            SchemaField schemaField = new SchemaField(
                    field.number,
                    field.name,
                    field.type,
                    message ? normalizedType : "",
                    enumField ? normalizedType : "",
                    field.repeated,
                    field.packed);
            byNumber.put(schemaField.number(), schemaField);
            byName.put(schemaField.name(), schemaField);
        }
        messages.put(typeName, new SchemaMessage(typeName, Map.copyOf(byNumber), Map.copyOf(byName)));
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

    private static boolean isScalar(String type) {
        return switch (type) {
            case "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
                    "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes" -> true;
            default -> false;
        };
    }

    private record ProtoFile(String packageName, Set<String> enums, List<ProtoMessage> messages) {
        private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");

        static ProtoFile parse(String source) {
            String stripped = stripComments(source);
            Matcher packageMatcher = PACKAGE.matcher(stripped);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
            return new ProtoFile(packageName, parseEnums(packageName, stripped), parseMessages(stripped));
        }

        private static Set<String> parseEnums(String packageName, String source) {
            Set<String> enums = new HashSet<>();
            Matcher matcher = Pattern.compile("\\benum\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{").matcher(source);
            while (matcher.find()) {
                String name = matcher.group(1);
                enums.add(name);
                if (!packageName.isBlank()) {
                    enums.add(packageName + "." + name);
                }
            }
            return enums;
        }

        private static List<ProtoMessage> parseMessages(String source) {
            List<ProtoMessage> messages = new ArrayList<>();
            Matcher matcher = Pattern.compile("\\bmessage\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{").matcher(source);
            while (matcher.find()) {
                int bodyStart = matcher.end();
                int bodyEnd = findBlockEnd(source, bodyStart - 1);
                if (bodyEnd > bodyStart) {
                    messages.add(new ProtoMessage(matcher.group(1), parseFields(source.substring(bodyStart, bodyEnd))));
                }
            }
            return messages;
        }

        private static List<ProtoField> parseFields(String body) {
            List<ProtoField> fields = new ArrayList<>();
            Pattern fieldPattern = Pattern.compile(
                    "\\b(optional|required|repeated)?\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\\d+)([^;]*);");
            Matcher matcher = fieldPattern.matcher(body);
            while (matcher.find()) {
                boolean repeated = "repeated".equals(matcher.group(1));
                boolean packed = matcher.group(5).contains("packed") && matcher.group(5).contains("true");
                fields.add(new ProtoField(matcher.group(3), matcher.group(2), Integer.parseInt(matcher.group(4)), repeated, packed));
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

    private record ProtoMessage(String name, List<ProtoField> fields) {
    }

    private record ProtoField(String name, String type, int number, boolean repeated, boolean packed) {
    }
}
