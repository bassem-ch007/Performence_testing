package com.iyedbassem.orderservice.services;

import com.iyedbassem.orderservice.dtos.CartItemRequest;
import com.iyedbassem.orderservice.entities.CartItem;

import java.util.List;

public interface CartItemService {
    Boolean AddToCart(String userId, CartItemRequest cartItemRequest);
    boolean deleteItemFromCart(String userId, Long productId);
    List<CartItem> getCart(String userId);
    void cleanCart(String userId);
}
