package com.iyedbassem.ecommercemono.services;

import com.iyedbassem.ecommercemono.dtos.OrderResponse;

import java.util.Optional;

public interface OrderService {
    Optional<OrderResponse> createOrder(String userId);
}
