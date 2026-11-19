package com.iyedbassem.ecommercemono.services;

import com.iyedbassem.ecommercemono.dtos.CartItemRequest;
import com.iyedbassem.ecommercemono.entities.CartItem;

import java.util.List;

public interface CartItemService {
    Boolean AddToCart(String user_id, CartItemRequest cartItemRequest);
    boolean deleteItemFromCart(String userId, Long productId);
    List<CartItem> getCart(String userId);
    void cleanCart(String userId);
}
