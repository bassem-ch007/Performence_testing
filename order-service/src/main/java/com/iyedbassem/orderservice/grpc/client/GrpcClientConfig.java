package com.iyedbassem.orderservice.grpc.client;

import com.iyedbassem.grpc.product.ProductGrpcServiceGrpc;
import com.iyedbassem.grpc.user.UserGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * gRPC client stubs. Channel names map to spring.grpc.client.channel.&lt;name&gt;.target.
 * Channels are long-lived and shared (HTTP/2 multiplexing), so one stub bean per service is enough.
 */
@Configuration
public class GrpcClientConfig {

    @Bean
    public UserGrpcServiceGrpc.UserGrpcServiceBlockingStub userStub(GrpcChannelFactory channels) {
        return UserGrpcServiceGrpc.newBlockingStub(channels.createChannel("user-service"));
    }

    @Bean
    public ProductGrpcServiceGrpc.ProductGrpcServiceBlockingStub productStub(GrpcChannelFactory channels) {
        return ProductGrpcServiceGrpc.newBlockingStub(channels.createChannel("product-service"));
    }
}
