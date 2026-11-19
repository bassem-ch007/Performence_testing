package com.iyedbassem.orderservice.grpc.client;

import com.iyedbassem.grpc.product.GetProductRequest;
import com.iyedbassem.grpc.product.ProductGrpcServiceGrpc;
import com.iyedbassem.grpc.product.ProductReply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Calls product-service over gRPC (replaces ProductRepository lookups of the monolith). */
@Component
@RequiredArgsConstructor
public class ProductClient {
    private static final long DEADLINE_MS = 5_000;
    private final ProductGrpcServiceGrpc.ProductGrpcServiceBlockingStub productStub;

    public Optional<ProductReply> findById(Long id) {
        ProductReply reply = productStub
                .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .getProduct(GetProductRequest.newBuilder().setId(id).build());
        return reply.getFound() ? Optional.of(reply) : Optional.empty();
    }
}
