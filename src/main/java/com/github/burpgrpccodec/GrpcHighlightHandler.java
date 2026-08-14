package com.github.burpgrpccodec;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;

final class GrpcHighlightHandler implements HttpHandler {
    private final GrpcTranscoder transcoder;
    private final ExtensionSettings settings;

    GrpcHighlightHandler(GrpcTranscoder transcoder, ExtensionSettings settings) {
        this.transcoder = transcoder;
        this.settings = settings;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        return RequestToBeSentAction.continueWith(requestToBeSent);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        if (!settings.autoHighlight()) {
            return ResponseReceivedAction.continueWith(responseReceived);
        }
        Annotations annotations = responseReceived.annotations();
        if (annotations.hasHighlightColor()) {
            return ResponseReceivedAction.continueWith(responseReceived);
        }
        HttpHeaders headers = HttpHeaders.from(responseReceived, responseReceived.initiatingRequest());
        if (!transcoder.isDeclaredGrpcOrProtobuf(headers)) {
            return ResponseReceivedAction.continueWith(responseReceived);
        }
        Annotations updated = annotations.withHighlightColor(HighlightColor.GREEN);
        if (!annotations.hasNotes()) {
            updated = updated.withNotes("gRPC/protobuf detected");
        }
        return ResponseReceivedAction.continueWith(responseReceived, updated);
    }
}
