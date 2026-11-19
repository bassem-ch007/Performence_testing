package com.iyedbassem.userservice.grpc;

import com.iyedbassem.grpc.user.GetUserRequest;
import com.iyedbassem.grpc.user.UserGrpcServiceGrpc;
import com.iyedbassem.grpc.user.UserReply;
import com.iyedbassem.userservice.services.UserService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * gRPC server endpoint of user-service (listens on spring.grpc.server.port).
 * Every BindableService bean is registered automatically by Spring gRPC.
 */
@Service
@RequiredArgsConstructor
public class UserGrpcServer extends UserGrpcServiceGrpc.UserGrpcServiceImplBase {
    private final UserService userService;

    @Override
    public void getUser(GetUserRequest request, StreamObserver<UserReply> responseObserver) {
        UserReply reply = userService.getUserbyID(request.getId())
                .map(user -> UserReply.newBuilder()
                        .setFound(true)
                        .setId(Long.parseLong(user.getId()))
                        .setFirstName(Objects.toString(user.getFirstName(), ""))
                        .setLastName(Objects.toString(user.getLastName(), ""))
                        .setEmail(Objects.toString(user.getEmail(), ""))
                        .setPhone(Objects.toString(user.getPhone(), ""))
                        .build())
                .orElseGet(() -> UserReply.newBuilder().setFound(false).setId(request.getId()).build());
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
