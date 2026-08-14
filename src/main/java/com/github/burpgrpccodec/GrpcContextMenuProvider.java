package com.github.burpgrpccodec;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class GrpcContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final GrpcTranscoder transcoder;

    GrpcContextMenuProvider(MontoyaApi api, GrpcTranscoder transcoder) {
        this.api = api;
        this.transcoder = transcoder;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> targets = event.messageEditorRequestResponse()
                .map(editor -> List.of(editor.requestResponse()))
                .orElseGet(event::selectedRequestResponses);
        if (targets.isEmpty()) {
            return List.of();
        }
        JMenuItem item = new JMenuItem("Log decoded gRPC/protobuf body");
        item.addActionListener(actionEvent -> logDecoded(targets));
        return List.of(item);
    }

    private void logDecoded(List<HttpRequestResponse> targets) {
        int decodedCount = 0;
        for (HttpRequestResponse requestResponse : targets) {
            decodedCount += logRequest(requestResponse.request());
            decodedCount += logResponse(requestResponse.response(), requestResponse.request());
        }
        if (decodedCount == 0) {
            api.logging().logToOutput("gRPC Codec: no gRPC/protobuf request or response body found in selection.");
        }
    }

    private int logRequest(HttpRequest request) {
        if (request == null) {
            return 0;
        }
        HttpHeaders headers = HttpHeaders.from(request);
        byte[] body = request.body().getBytes();
        if (!transcoder.isCandidate(body, headers)) {
            return 0;
        }
        try {
            byte[] decoded = transcoder.decode(body, headers);
            api.logging().logToOutput("gRPC Codec: decoded request " + request.url() + "\n"
                    + new String(decoded, StandardCharsets.UTF_8));
            return 1;
        } catch (RuntimeException ex) {
            api.logging().logToError("gRPC Codec: failed to decode request " + request.url() + ": " + ex.getMessage());
            return 0;
        }
    }

    private int logResponse(HttpResponse response, HttpRequest request) {
        if (response == null) {
            return 0;
        }
        HttpHeaders headers = HttpHeaders.from(response, request);
        byte[] body = response.body().getBytes();
        if (!transcoder.isCandidate(body, headers)) {
            return 0;
        }
        try {
            byte[] decoded = transcoder.decode(body, headers);
            String url = request == null ? "(no request)" : request.url();
            api.logging().logToOutput("gRPC Codec: decoded response for " + url + "\n"
                    + new String(decoded, StandardCharsets.UTF_8));
            return 1;
        } catch (RuntimeException ex) {
            api.logging().logToError("gRPC Codec: failed to decode response: " + ex.getMessage());
            return 0;
        }
    }
}
