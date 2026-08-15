package com.github.burpgrpccodec;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public final class BurpGrpcCodecExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp gRPC Codec");
        ExtensionSettings settings = new ExtensionSettings(api.persistence().extensionData());
        SchemaRegistry schemas = new SchemaRegistry();
        GrpcTranscoder transcoder = new GrpcTranscoder(schemas, settings);
        api.userInterface().registerSettingsPanel(settings.panel());
        api.userInterface().registerHttpRequestEditorProvider(
                context -> new GrpcRequestEditor(api, transcoder));
        api.userInterface().registerHttpResponseEditorProvider(
                context -> new GrpcResponseEditor(api, transcoder));
        api.userInterface().registerContextMenuItemsProvider(new GrpcContextMenuProvider(api, transcoder));
        api.http().registerHttpHandler(new GrpcHighlightHandler(transcoder, settings));
        GrpcMethodDiscoveryLog discoveryLog = new GrpcMethodDiscoveryLog(api.persistence().extensionData());
        api.http().registerHttpHandler(new GrpcMethodDiscoveryHandler(transcoder, discoveryLog));
        api.userInterface().registerSuiteTab("gRPC Methods", new GrpcMethodDiscoveryPanel(discoveryLog).uiComponent());
        api.logging().logToOutput("Burp gRPC Codec loaded");
    }
}
