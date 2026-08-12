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
    private final GrpcTranscoder transcoder = new GrpcTranscoder();
    private HttpRequestResponse current;

    GrpcRequestEditor(MontoyaApi api) {
        this.editor = api.userInterface().createRawEditor();
        this.editor.setEditable(true);
    }

    @Override
    public HttpRequest getRequest() {
        if (current == null || !editor.isModified()) {
            return current == null ? null : current.request();
        }
        byte[] edited = editor.getContents().getBytes();
        byte[] body = transcoder.encode(edited, HttpHeaders.from(current.request()));
        return current.request().withBody(ByteArray.byteArray(body));
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.current = requestResponse;
        if (requestResponse == null || requestResponse.request() == null) {
            editor.setContents(ByteArray.byteArray(new byte[0]));
            return;
        }
        try {
            byte[] decoded = transcoder.decode(requestResponse.request().body().getBytes(), HttpHeaders.from(requestResponse.request()));
            editor.setContents(ByteArray.byteArray(decoded));
        } catch (RuntimeException ex) {
            editor.setContents(ByteArray.byteArray(("Decode error: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8)));
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
