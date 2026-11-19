package com.iyedbassem.orderservice.grpc.client;

import com.iyedbassem.grpc.user.GetUserRequest;
import com.iyedbassem.grpc.user.UserGrpcServiceGrpc;
import com.iyedbassem.grpc.user.UserReply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Calls user-service over gRPC (replaces UserRepository lookups of the monolith). */
@Component
@RequiredArgsConstructor
public class UserClient {
    private static final long DEADLINE_MS = 5_000;
    private final UserGrpcServiceGrpc.UserGrpcServiceBlockingStub userStub;

    public Optional<UserReply> findById(Long id) {
        UserReply reply = userStub
                .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .getUser(GetUserRequest.newBuilder().setId(id).build());
        return reply.getFound() ? Optional.of(reply) : Optional.empty();
    }
}
