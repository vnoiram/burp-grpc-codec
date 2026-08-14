package com.github.burpgrpccodec;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;

import java.awt.Component;
import java.nio.charset.StandardCharsets;

final class GrpcRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final RawEditor editor;
    private final MontoyaApi api;
    private final GrpcTranscoder transcoder;
    private HttpRequestResponse current;
    private boolean decoded;

    GrpcRequestEditor(MontoyaApi api, GrpcTranscoder transcoder) {
        this.api = api;
        this.transcoder = transcoder;
        this.editor = api.userInterface().createRawEditor();
        this.editor.setEditable(true);
    }

    @Override
    public HttpRequest getRequest() {
        if (current == null || !decoded || !editor.isModified()) {
            return current == null ? null : current.request();
        }
        try {
            byte[] edited = editor.getContents().getBytes();
            byte[] body = transcoder.encode(edited, HttpHeaders.from(current.request()));
            if (transcoder.verboseLogging()) {
                api.logging().logToOutput("gRPC Codec: encoded edited request body (" + body.length + " bytes)");
            }
            return current.request().withBody(ByteArray.byteArray(body));
        } catch (RuntimeException ex) {
            api.logging().logToError("gRPC Codec request encode error: " + ex.getMessage());
            return current.request();
        }
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.current = requestResponse;
        this.decoded = false;
        if (requestResponse == null || requestResponse.request() == null) {
            editor.setContents(ByteArray.byteArray(new byte[0]));
            editor.setEditable(false);
            return;
        }
        try {
            transcoder.reloadSchemas();
            if (transcoder.verboseLogging()) {
                api.logging().logToOutput("gRPC Codec: schema reload -> " + transcoder.schemaSummary());
            }
            byte[] decoded = transcoder.decode(requestResponse.request().body().getBytes(), HttpHeaders.from(requestResponse.request()));
            editor.setContents(ByteArray.byteArray(decoded));
            editor.setEditable(true);
            this.decoded = true;
            if (transcoder.verboseLogging()) {
                api.logging().logToOutput("gRPC Codec: decoded request body for " + requestResponse.request().url());
            }
            if (transcoder.autoSelectTab()) {
                MessageEditorTabActivator.selectTabContaining(editor.uiComponent());
            }
        } catch (RuntimeException ex) {
            editor.setContents(ByteArray.byteArray(("Decode error: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8)));
            editor.setEditable(false);
        }
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        return requestResponse != null
                && requestResponse.request() != null
                && transcoder.isCandidate(requestResponse.request().body().getBytes(), HttpHeaders.from(requestResponse.request()));
    }

    @Override
    public String caption() {
        return "gRPC Codec";
    }

    @Override
    public Component uiComponent() {
        return editor.uiComponent();
    }

    @Override
    public Selection selectedData() {
        return editor.selection().orElse(null);
    }

    @Override
    public boolean isModified() {
        return editor.isModified();
    }
}
