package com.iyedbassem.orderservice.services;

import com.iyedbassem.orderservice.dtos.OrderResponse;

import java.util.Optional;

public interface OrderService {
    Optional<OrderResponse> createOrder(String userId);
    Optional<OrderResponse> getOrder(Long orderId);
}
