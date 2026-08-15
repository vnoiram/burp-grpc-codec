package com.github.burpgrpccodec;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionSettingsTest {
    @Test
    void parsesMultiplePathOverrides() {
        Map<String, ExtensionSettings.PathTypeOverride> overrides = ExtensionSettings.parseMessageTypeOverrides(
                "/demo.Greeter/SayHello=demo.HelloRequest>demo.HelloResponse;"
                        + "/demo.Greeter/SayBye=demo.ByeRequest>demo.ByeResponse");

        assertEquals(2, overrides.size());
        assertEquals(new ExtensionSettings.PathTypeOverride("demo.HelloRequest", "demo.HelloResponse"),
                overrides.get("/demo.Greeter/SayHello"));
        assertEquals(new ExtensionSettings.PathTypeOverride("demo.ByeRequest", "demo.ByeResponse"),
                overrides.get("/demo.Greeter/SayBye"));
    }

    @Test
    void allowsRequestTypeOnlyWithoutResponseType() {
        Map<String, ExtensionSettings.PathTypeOverride> overrides = ExtensionSettings.parseMessageTypeOverrides(
                "/demo.Greeter/SayHello=demo.HelloRequest");

        ExtensionSettings.PathTypeOverride override = overrides.get("/demo.Greeter/SayHello");
        assertEquals("demo.HelloRequest", override.requestType());
        assertEquals("", override.responseType());
    }

    @Test
    void ignoresBlankAndMalformedEntries() {
        Map<String, ExtensionSettings.PathTypeOverride> overrides = ExtensionSettings.parseMessageTypeOverrides(
                "  ;/no-equals-sign;/demo.Greeter/SayHi=;=demo.NoPath");

        assertTrue(overrides.isEmpty());
    }

    @Test
    void returnsEmptyMapForBlankInput() {
        assertTrue(ExtensionSettings.parseMessageTypeOverrides("").isEmpty());
        assertTrue(ExtensionSettings.parseMessageTypeOverrides("   ").isEmpty());
    }
}
