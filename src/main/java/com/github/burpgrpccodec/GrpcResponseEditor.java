package com.github.burpgrpccodec;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;

import java.awt.Component;
import java.nio.charset.StandardCharsets;

final class GrpcResponseEditor implements ExtensionProvidedHttpResponseEditor {
    private final RawEditor editor;
    private final GrpcTranscoder transcoder = new GrpcTranscoder();
    private HttpRequestResponse current;

    GrpcResponseEditor(MontoyaApi api) {
        this.editor = api.userInterface().createRawEditor();
        this.editor.setEditable(true);
    }

    @Override
    public HttpResponse getResponse() {
        if (current == null || current.response() == null || !editor.isModified()) {
            return current == null ? null : current.response();
        }
        byte[] edited = editor.getContents().getBytes();
        byte[] body = transcoder.encode(edited, HttpHeaders.from(current.response()));
        return current.response().withBody(ByteArray.byteArray(body));
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.current = requestResponse;
        if (requestResponse == null || requestResponse.response() == null) {
            editor.setContents(ByteArray.byteArray(new byte[0]));
            return;
        }
        try {
            byte[] decoded = transcoder.decode(requestResponse.response().body().getBytes(), HttpHeaders.from(requestResponse.response()));
            editor.setContents(ByteArray.byteArray(decoded));
        } catch (RuntimeException ex) {
            editor.setContents(ByteArray.byteArray(("Decode error: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        return requestResponse != null
                && requestResponse.response() != null
                && transcoder.isCandidate(requestResponse.response().body().getBytes(), HttpHeaders.from(requestResponse.response()));
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
