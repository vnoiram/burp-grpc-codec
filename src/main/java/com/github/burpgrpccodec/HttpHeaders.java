package com.github.burpgrpccodec;

import burp.api.montoya.http.message.HttpMessage;

import java.util.Locale;
import java.util.Optional;

record HttpHeaders(String contentType) {
    static HttpHeaders from(HttpMessage message) {
        Optional<String> contentType = message.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase("content-type"))
                .map(header -> header.value().toLowerCase(Locale.ROOT))
                .findFirst();
        return new HttpHeaders(contentType.orElse(""));
    }

    boolean isGrpc() {
        return contentType.contains("application/grpc");
    }

    boolean isGrpcWeb() {
        return contentType.contains("application/grpc-web");
    }

    boolean isGrpcWebText() {
        return contentType.contains("application/grpc-web-text");
    }
}
