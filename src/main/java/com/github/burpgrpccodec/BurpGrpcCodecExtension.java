package com.github.burpgrpccodec;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public final class BurpGrpcCodecExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp gRPC Codec");
        api.userInterface().registerHttpRequestEditorProvider(
                context -> new GrpcRequestEditor(api));
        api.userInterface().registerHttpResponseEditorProvider(
                context -> new GrpcResponseEditor(api));
        api.logging().logToOutput("Burp gRPC Codec loaded");
    }
}
