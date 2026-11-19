package com.iyedbassem.productservice.grpc;

import com.iyedbassem.grpc.product.GetProductRequest;
import com.iyedbassem.grpc.product.ProductGrpcServiceGrpc;
import com.iyedbassem.grpc.product.ProductReply;
import com.iyedbassem.productservice.services.ProductService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * gRPC server endpoint of product-service (listens on spring.grpc.server.port).
 * Every BindableService bean is registered automatically by Spring gRPC.
 */
@Service
@RequiredArgsConstructor
public class ProductGrpcServer extends ProductGrpcServiceGrpc.ProductGrpcServiceImplBase {
    private final ProductService productService;

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductReply> responseObserver) {
        ProductReply reply = productService.getProductByID(request.getId())
                .map(product -> ProductReply.newBuilder()
                        .setFound(true)
                        .setId(Long.parseLong(product.getId()))
                        .setName(Objects.toString(product.getName(), ""))
                        .setPrice(Objects.requireNonNullElse(product.getPrice(), BigDecimal.ZERO).toPlainString())
                        .setStockQuantity(Objects.requireNonNullElse(product.getStockQuantity(), 0))
                        .setActive(Boolean.TRUE.equals(product.getActive()))
                        .build())
                .orElseGet(() -> ProductReply.newBuilder().setFound(false).setId(request.getId()).build());
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
