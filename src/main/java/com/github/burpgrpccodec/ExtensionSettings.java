package com.github.burpgrpccodec;

import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.settings.SettingsPanelSetting;
import burp.api.montoya.ui.settings.SettingsPanelWithData;

import static burp.api.montoya.ui.settings.SettingsPanelBuilder.settingsPanel;
import static burp.api.montoya.ui.settings.SettingsPanelPersistence.PROJECT_SETTINGS;

final class ExtensionSettings {
    private static final String PROTO_PATHS = "Proto paths";
    private static final String REFLECTION_TARGET = "Reflection host:port";
    private static final String REFLECTION_TLS = "Reflection TLS";
    private static final String DEFAULT_REQUEST_TYPE = "Default request type";
    private static final String DEFAULT_RESPONSE_TYPE = "Default response type";
    private static final String RAW_DETECTION = "Raw protobuf detection";
    private static final String REFLECTION_TIMEOUT = "Reflection timeout (seconds)";
    private static final String JSON_OUTPUT = "JSON output style";
    private static final String VERBOSE_LOGGING = "Verbose logging";
    private static final String MAX_DEPTH = "Max message nesting depth";
    private static final String AUTO_HIGHLIGHT = "Auto-highlight gRPC/protobuf traffic";

    private final PersistedObject data;
    private final SettingsPanelWithData panel;

    ExtensionSettings(PersistedObject data) {
        this.data = data;
        this.panel = settingsPanel()
                .withPersistence(PROJECT_SETTINGS)
                .withTitle("Burp gRPC Codec")
                .withDescription("Schema, reflection, compression, and raw protobuf detection settings.")
                .withKeywords("grpc", "protobuf", "proto", "reflection")
                .withSetting(SettingsPanelSetting.stringSetting(PROTO_PATHS,
                        "Comma-separated .proto files or directories."))
                .withSetting(SettingsPanelSetting.stringSetting(REFLECTION_TARGET,
                        "host:port for gRPC Server Reflection."))
                .withSetting(SettingsPanelSetting.booleanSetting(REFLECTION_TLS,
                        "Use TLS for Server Reflection.", false))
                .withSetting(SettingsPanelSetting.stringSetting(REFLECTION_TIMEOUT,
                        "Server Reflection request timeout in seconds."))
                .withSetting(SettingsPanelSetting.stringSetting(DEFAULT_REQUEST_TYPE,
                        "Fully-qualified protobuf message type for request bodies."))
                .withSetting(SettingsPanelSetting.stringSetting(DEFAULT_RESPONSE_TYPE,
                        "Fully-qualified protobuf message type for response bodies."))
                .withSetting(SettingsPanelSetting.listSetting(RAW_DETECTION,
                        "Fallback detection for bodies without gRPC/protobuf Content-Type.",
                        java.util.List.of("broad", "strict", "off"), "broad"))
                .withSetting(SettingsPanelSetting.listSetting(JSON_OUTPUT,
                        "Editor JSON formatting.",
                        java.util.List.of("pretty", "compact"), "pretty"))
                .withSetting(SettingsPanelSetting.booleanSetting(VERBOSE_LOGGING,
                        "Log schema reload and decode/encode activity to the extension output.", false))
                .withSetting(SettingsPanelSetting.stringSetting(MAX_DEPTH,
                        "Maximum nested message depth to decode."))
                .withSetting(SettingsPanelSetting.booleanSetting(AUTO_HIGHLIGHT,
                        "Highlight and annotate detected gRPC/protobuf traffic in Proxy history.", false))
                .build();
        loadDefaults();
    }

    SettingsPanelWithData panel() {
        return panel;
    }

    String protoPaths() {
        return value(PROTO_PATHS);
    }

    String reflectionTarget() {
        return value(REFLECTION_TARGET);
    }

    boolean reflectionTls() {
        return panel.getBoolean(REFLECTION_TLS);
    }

    String defaultRequestType() {
        return value(DEFAULT_REQUEST_TYPE);
    }

    String defaultResponseType() {
        return value(DEFAULT_RESPONSE_TYPE);
    }

    RawDetection rawDetection() {
        return RawDetection.from(value(RAW_DETECTION));
    }

    int reflectionTimeoutSeconds() {
        try {
            int seconds = Integer.parseInt(value(REFLECTION_TIMEOUT));
            return seconds > 0 ? seconds : 5;
        } catch (NumberFormatException ex) {
            return 5;
        }
    }

    boolean prettyJson() {
        return !"compact".equalsIgnoreCase(value(JSON_OUTPUT));
    }

    boolean verboseLogging() {
        Boolean verbose = panel.getBoolean(VERBOSE_LOGGING);
        if (verbose == null) {
            verbose = data.getBoolean(VERBOSE_LOGGING);
        }
        return Boolean.TRUE.equals(verbose);
    }

    boolean autoHighlight() {
        Boolean highlight = panel.getBoolean(AUTO_HIGHLIGHT);
        if (highlight == null) {
            highlight = data.getBoolean(AUTO_HIGHLIGHT);
        }
        return Boolean.TRUE.equals(highlight);
    }

    int maxDepth() {
        try {
            int depth = Integer.parseInt(value(MAX_DEPTH));
            return depth > 0 ? depth : 24;
        } catch (NumberFormatException ex) {
            return 24;
        }
    }

    void saveSnapshot() {
        data.setString(PROTO_PATHS, protoPaths());
        data.setString(REFLECTION_TARGET, reflectionTarget());
        data.setBoolean(REFLECTION_TLS, reflectionTls());
        data.setString(REFLECTION_TIMEOUT, value(REFLECTION_TIMEOUT));
        data.setString(DEFAULT_REQUEST_TYPE, defaultRequestType());
        data.setString(DEFAULT_RESPONSE_TYPE, defaultResponseType());
        data.setString(RAW_DETECTION, rawDetection().value);
        data.setString(JSON_OUTPUT, value(JSON_OUTPUT));
        data.setBoolean(VERBOSE_LOGGING, verboseLogging());
        data.setString(MAX_DEPTH, value(MAX_DEPTH));
        data.setBoolean(AUTO_HIGHLIGHT, autoHighlight());
    }

    private void loadDefaults() {
        setDefault(PROTO_PATHS, "");
        setDefault(REFLECTION_TARGET, "");
        setDefault(REFLECTION_TIMEOUT, "5");
        setDefault(DEFAULT_REQUEST_TYPE, "");
        setDefault(DEFAULT_RESPONSE_TYPE, "");
        setDefault(RAW_DETECTION, "broad");
        setDefault(JSON_OUTPUT, "pretty");
        setDefault(MAX_DEPTH, "24");
        Boolean tls = data.getBoolean(REFLECTION_TLS);
        if (tls == null) {
            data.setBoolean(REFLECTION_TLS, false);
        }
        Boolean verbose = data.getBoolean(VERBOSE_LOGGING);
        if (verbose == null) {
            data.setBoolean(VERBOSE_LOGGING, false);
        }
        Boolean highlight = data.getBoolean(AUTO_HIGHLIGHT);
        if (highlight == null) {
            data.setBoolean(AUTO_HIGHLIGHT, false);
        }
    }

    private void setDefault(String key, String defaultValue) {
        if (data.getString(key) == null) {
            data.setString(key, defaultValue);
        }
    }

    private String value(String key) {
        String value = panel.getString(key);
        if (value == null) {
            value = data.getString(key);
        }
        return value == null ? "" : value.trim();
    }

    enum RawDetection {
        BROAD("broad"),
        STRICT("strict"),
        OFF("off");

        private final String value;

        RawDetection(String value) {
            this.value = value;
        }

        static RawDetection from(String value) {
            for (RawDetection mode : values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return BROAD;
        }
    }
}
