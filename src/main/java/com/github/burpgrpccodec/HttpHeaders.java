package com.github.burpgrpccodec;

import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.Locale;
import java.util.Optional;

record HttpHeaders(String contentType, String grpcEncoding, String grpcPath, boolean response) {
    HttpHeaders(String contentType) {
        this(contentType, "", "", false);
    }

    HttpHeaders(String contentType, String grpcEncoding) {
        this(contentType, grpcEncoding, "", false);
    }

    static HttpHeaders from(HttpRequest request) {
        return from(request, request.pathWithoutQuery(), false);
    }

    static HttpHeaders from(HttpResponse response, HttpRequest request) {
        String path = request == null ? "" : request.pathWithoutQuery();
        return from(response, path, true);
    }

    private static HttpHeaders from(HttpMessage message, String grpcPath, boolean response) {
        Optional<String> contentType = message.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase("content-type"))
                .map(header -> header.value().toLowerCase(Locale.ROOT))
                .findFirst();
        Optional<String> grpcEncoding = message.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase("grpc-encoding"))
                .map(header -> header.value().toLowerCase(Locale.ROOT))
                .findFirst();
        return new HttpHeaders(contentType.orElse(""), grpcEncoding.orElse(""), grpcPath, response);
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
