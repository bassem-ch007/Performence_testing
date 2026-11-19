package com.iyedbassem.orderservice.grpc;

import com.iyedbassem.grpc.order.GetOrderRequest;
import com.iyedbassem.grpc.order.OrderGrpcServiceGrpc;
import com.iyedbassem.grpc.order.OrderItemReply;
import com.iyedbassem.grpc.order.OrderReply;
import com.iyedbassem.orderservice.services.OrderService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * gRPC server endpoint of order-service (listens on spring.grpc.server.port).
 * Every BindableService bean is registered automatically by Spring gRPC.
 */
@Service
@RequiredArgsConstructor
public class OrderGrpcServer extends OrderGrpcServiceGrpc.OrderGrpcServiceImplBase {
    private final OrderService orderService;

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<OrderReply> responseObserver) {
        OrderReply reply = orderService.getOrder(request.getId())
                .map(order -> OrderReply.newBuilder()
                        .setFound(true)
                        .setId(order.getId())
                        .setTotalAmount(order.getTotalAmount().toPlainString())
                        .setStatus(Objects.toString(order.getStatus(), ""))
                        .setCreatedAt(Objects.toString(order.getCreatedAt(), ""))
                        .addAllItems(order.getItems().stream()
                                .map(item -> OrderItemReply.newBuilder()
                                        .setId(item.getId())
                                        .setProductId(item.getProductId())
                                        .setQuantity(item.getQuantity())
                                        .setPrice(item.getPrice().toPlainString())
                                        .setSubTotal(item.getSubTotal().toPlainString())
                                        .build())
                                .toList())
                        .build())
                .orElseGet(() -> OrderReply.newBuilder().setFound(false).setId(request.getId()).build());
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
