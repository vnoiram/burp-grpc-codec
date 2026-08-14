package com.github.burpgrpccodec;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ReflectionSchemaLoader {
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    Optional<DescriptorProtos.FileDescriptorSet> load(String target, boolean tls) {
        return load(target, tls, DEFAULT_TIMEOUT_SECONDS);
    }

    Optional<DescriptorProtos.FileDescriptorSet> load(String target, boolean tls, int timeoutSeconds) {
        HostPort hostPort = HostPort.parse(target);
        if (hostPort == null) {
            return Optional.empty();
        }
        int deadline = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(hostPort.host, hostPort.port);
        if (!tls) {
            builder.usePlaintext();
        }
        ManagedChannel channel = builder.build();
        try {
            DescriptorProtos.FileDescriptorSet.Builder descriptors = DescriptorProtos.FileDescriptorSet.newBuilder();
            ReflectionCollector serviceCollector = new ReflectionCollector(deadline);
            StreamObserver<ServerReflectionRequest> serviceRequests = ServerReflectionGrpc.newStub(channel)
                    .withDeadlineAfter(deadline, TimeUnit.SECONDS)
                    .serverReflectionInfo(serviceCollector);
            serviceRequests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
            serviceRequests.onCompleted();
            if (!serviceCollector.await()) {
                return Optional.empty();
            }
            for (String serviceName : serviceCollector.services()) {
                loadService(channel, serviceName, descriptors, deadline);
            }
            return Optional.of(descriptors.build());
        } catch (RuntimeException ex) {
            return Optional.empty();
        } finally {
            channel.shutdownNow();
        }
    }

    private void loadService(
            ManagedChannel channel,
            String serviceName,
            DescriptorProtos.FileDescriptorSet.Builder descriptors,
            int timeoutSeconds
    ) {
        ReflectionCollector descriptorCollector = new ReflectionCollector(timeoutSeconds);
        StreamObserver<ServerReflectionRequest> descriptorRequests = ServerReflectionGrpc.newStub(channel)
                .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
                .serverReflectionInfo(descriptorCollector);
        descriptorRequests.onNext(ServerReflectionRequest.newBuilder().setFileContainingSymbol(serviceName).build());
        descriptorRequests.onCompleted();
        if (descriptorCollector.await()) {
            descriptorCollector.descriptors().forEach(descriptors::addFile);
        }
    }

    private static final class ReflectionCollector implements StreamObserver<ServerReflectionResponse> {
        private final CountDownLatch done = new CountDownLatch(1);
        private final int timeoutSeconds;
        private final java.util.List<String> services = new java.util.ArrayList<>();
        private final java.util.List<DescriptorProtos.FileDescriptorProto> descriptors = new java.util.ArrayList<>();

        ReflectionCollector(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public void onNext(ServerReflectionResponse response) {
            response.getListServicesResponse().getServiceList().forEach(service -> services.add(service.getName()));
            response.getFileDescriptorResponse().getFileDescriptorProtoList().forEach(bytes -> {
                try {
                    descriptors.add(DescriptorProtos.FileDescriptorProto.parseFrom(bytes));
                } catch (InvalidProtocolBufferException ignored) {
                    // Ignore invalid reflection entries and keep any usable descriptors.
                }
            });
        }

        @Override
        public void onError(Throwable throwable) {
            done.countDown();
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }

        boolean await() {
            try {
                return done.await(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        java.util.List<String> services() {
            return services;
        }

        java.util.List<DescriptorProtos.FileDescriptorProto> descriptors() {
            return descriptors;
        }
    }

    private record HostPort(String host, int port) {
        static HostPort parse(String target) {
            int index = target.lastIndexOf(':');
            if (index <= 0 || index == target.length() - 1) {
                return null;
            }
            try {
                return new HostPort(target.substring(0, index), Integer.parseInt(target.substring(index + 1)));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
