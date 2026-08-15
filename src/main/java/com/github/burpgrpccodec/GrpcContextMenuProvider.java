package com.github.burpgrpccodec;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class GrpcContextMenuProvider implements ContextMenuItemsProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        JMenuItem logItem = new JMenuItem("Log decoded gRPC/protobuf body");
        logItem.addActionListener(actionEvent -> logDecoded(targets));
        JMenuItem copyItem = new JMenuItem("Copy decoded gRPC/protobuf body to clipboard");
        copyItem.addActionListener(actionEvent -> copyDecoded(targets));
        JMenuItem comparerItem = new JMenuItem("Send decoded gRPC/protobuf bodies to Comparer");
        comparerItem.addActionListener(actionEvent -> sendToComparer(targets));
        JMenuItem grpcurlItem = new JMenuItem("Copy as grpcurl command");
        grpcurlItem.addActionListener(actionEvent -> copyAsGrpcurl(targets));
        JMenuItem protoStubItem = new JMenuItem("Generate .proto stub from decoded body");
        protoStubItem.addActionListener(actionEvent -> generateProtoStub(targets));
        return List.of(logItem, copyItem, comparerItem, grpcurlItem, protoStubItem);
    }

    private void logDecoded(List<HttpRequestResponse> targets) {
        int decodedCount = 0;
        for (HttpRequestResponse requestResponse : targets) {
            Optional<String> request = decodeRequestBody(requestResponse.request());
            request.ifPresent(json -> api.logging().logToOutput(
                    "gRPC Codec: decoded request " + requestResponse.request().url() + "\n" + json));
            Optional<String> response = decodeResponseBody(requestResponse.response(), requestResponse.request());
            response.ifPresent(json -> api.logging().logToOutput(
                    "gRPC Codec: decoded response for " + requestUrl(requestResponse) + "\n" + json));
            decodedCount += (request.isPresent() ? 1 : 0) + (response.isPresent() ? 1 : 0);
        }
        if (decodedCount == 0) {
            api.logging().logToOutput("gRPC Codec: no gRPC/protobuf request or response body found in selection.");
        }
    }

    private void copyDecoded(List<HttpRequestResponse> targets) {
        for (HttpRequestResponse requestResponse : targets) {
            Optional<String> decoded = decodeRequestBody(requestResponse.request())
                    .or(() -> decodeResponseBody(requestResponse.response(), requestResponse.request()));
            if (decoded.isPresent()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(decoded.get()), null);
                api.logging().logToOutput("gRPC Codec: copied decoded body to clipboard.");
                return;
            }
        }
        api.logging().logToOutput("gRPC Codec: no gRPC/protobuf request or response body found in selection.");
    }

    private void sendToComparer(List<HttpRequestResponse> targets) {
        List<ByteArray> decodedBodies = new ArrayList<>();
        for (HttpRequestResponse requestResponse : targets) {
            decodeRequestBody(requestResponse.request())
                    .ifPresent(json -> decodedBodies.add(ByteArray.byteArray(json)));
            decodeResponseBody(requestResponse.response(), requestResponse.request())
                    .ifPresent(json -> decodedBodies.add(ByteArray.byteArray(json)));
        }
        if (decodedBodies.isEmpty()) {
            api.logging().logToOutput("gRPC Codec: no gRPC/protobuf request or response body found in selection.");
            return;
        }
        api.comparer().sendToComparer(decodedBodies.toArray(ByteArray[]::new));
    }

    private void copyAsGrpcurl(List<HttpRequestResponse> targets) {
        for (HttpRequestResponse requestResponse : targets) {
            HttpRequest request = requestResponse.request();
            if (request == null || request.httpService() == null) {
                continue;
            }
            HttpHeaders headers = HttpHeaders.from(request);
            if (!(headers.isGrpc() || headers.isGrpcWeb()) || headers.grpcPath().isBlank()) {
                continue;
            }
            byte[] body = request.body().getBytes();
            if (!transcoder.isCandidate(body, headers)) {
                continue;
            }
            try {
                JsonNode decoded = MAPPER.readTree(transcoder.decode(body, headers));
                JsonNode message = firstMessage(decoded);
                String hostAndPort = request.httpService().host() + ":" + request.httpService().port();
                String command = GrpcCurlFormatter.buildCommand(
                        hostAndPort, request.httpService().secure(), headers.grpcPath(), message);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(command), null);
                api.logging().logToOutput("gRPC Codec: copied grpcurl command to clipboard.");
            } catch (Exception ex) {
                api.logging().logToError("gRPC Codec: failed to build grpcurl command: " + ex.getMessage());
            }
            return;
        }
        api.logging().logToOutput("gRPC Codec: no gRPC/grpc-web request with a resolvable method path found in selection.");
    }

    private void generateProtoStub(List<HttpRequestResponse> targets) {
        for (HttpRequestResponse requestResponse : targets) {
            Optional<String> decodedJson = decodeRequestBody(requestResponse.request())
                    .or(() -> decodeResponseBody(requestResponse.response(), requestResponse.request()));
            if (decodedJson.isEmpty()) {
                continue;
            }
            try {
                JsonNode decoded = MAPPER.readTree(decodedJson.get());
                JsonNode message = firstMessage(decoded);
                String messageType = decoded.path("messageType").asText("");
                String rootName = messageType.isBlank() ? "DecodedMessage" : simpleTypeName(messageType);
                String stub = ProtoStubGenerator.generate(rootName, message);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(stub), null);
                api.logging().logToOutput("gRPC Codec: copied generated .proto stub to clipboard.");
            } catch (Exception ex) {
                api.logging().logToError("gRPC Codec: failed to generate .proto stub: " + ex.getMessage());
            }
            return;
        }
        api.logging().logToOutput("gRPC Codec: no gRPC/protobuf request or response body found in selection.");
    }

    private static JsonNode firstMessage(JsonNode decoded) {
        ArrayNode messages = decoded.withArray("messages");
        return messages.isEmpty() ? MAPPER.createObjectNode() : messages.get(0).path("message");
    }

    private static String simpleTypeName(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot < 0 ? fullyQualified : fullyQualified.substring(lastDot + 1);
    }

    private static String requestUrl(HttpRequestResponse requestResponse) {
        return requestResponse.request() == null ? "(no request)" : requestResponse.request().url();
    }

    private Optional<String> decodeRequestBody(HttpRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        HttpHeaders headers = HttpHeaders.from(request);
        byte[] body = request.body().getBytes();
        if (!transcoder.isCandidate(body, headers)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(transcoder.decode(body, headers), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            api.logging().logToError("gRPC Codec: failed to decode request " + request.url() + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> decodeResponseBody(HttpResponse response, HttpRequest request) {
        if (response == null) {
            return Optional.empty();
        }
        HttpHeaders headers = HttpHeaders.from(response, request);
        byte[] body = response.body().getBytes();
        if (!transcoder.isCandidate(body, headers)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(transcoder.decode(body, headers), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            api.logging().logToError("gRPC Codec: failed to decode response: " + ex.getMessage());
            return Optional.empty();
        }
    }
}
