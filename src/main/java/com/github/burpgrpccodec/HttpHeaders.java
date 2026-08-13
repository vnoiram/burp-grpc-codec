package com.github.burpgrpccodec;

import burp.api.montoya.http.message.HttpMessage;

import java.util.Locale;
import java.util.Optional;

record HttpHeaders(String contentType, String grpcEncoding) {
    HttpHeaders(String contentType) {
        this(contentType, "");
    }

    static HttpHeaders from(HttpMessage message) {
        Optional<String> contentType = message.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase("content-type"))
                .map(header -> header.value().toLowerCase(Locale.ROOT))
                .findFirst();
        Optional<String> grpcEncoding = message.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase("grpc-encoding"))
                .map(header -> header.value().toLowerCase(Locale.ROOT))
                .findFirst();
        return new HttpHeaders(contentType.orElse(""), grpcEncoding.orElse(""));
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

    boolean isProtobuf() {
        return contentType.contains("application/protobuf")
                || contentType.contains("application/x-protobuf")
                || contentType.contains("application/vnd.google.protobuf");
    }

    boolean isGzipEncoded() {
        return grpcEncoding.contains("gzip");
    }
}
