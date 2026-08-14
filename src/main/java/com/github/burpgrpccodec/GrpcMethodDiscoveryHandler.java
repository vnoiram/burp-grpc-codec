package com.github.burpgrpccodec;

import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;

final class GrpcMethodDiscoveryHandler implements HttpHandler {
    private final GrpcTranscoder transcoder;
    private final GrpcMethodDiscoveryLog log;

    GrpcMethodDiscoveryHandler(GrpcTranscoder transcoder, GrpcMethodDiscoveryLog log) {
        this.transcoder = transcoder;
        this.log = log;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        HttpHeaders headers = HttpHeaders.from(requestToBeSent);
        if (transcoder.isDeclaredGrpcOrProtobuf(headers) && !headers.grpcPath().isBlank()) {
            String host = requestToBeSent.httpService() == null ? "" : requestToBeSent.httpService().host();
            log.record(host, headers.grpcPath());
        }
        return RequestToBeSentAction.continueWith(requestToBeSent);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        return ResponseReceivedAction.continueWith(responseReceived);
    }
}
