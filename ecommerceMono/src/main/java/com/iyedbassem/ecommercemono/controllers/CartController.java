package com.iyedbassem.ecommercemono.controllers;


import com.iyedbassem.ecommercemono.dtos.CartItemRequest;
import com.iyedbassem.ecommercemono.entities.CartItem;
import com.iyedbassem.ecommercemono.services.CartItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/carts")
public class CartController {
    private final CartItemService cartItemService;
    @PostMapping
    public ResponseEntity<String> addToCart(
            @RequestHeader("X-User-ID") String userId,
            @RequestBody CartItemRequest cartItemRequest
    ){
        if (!cartItemService.AddToCart(userId,cartItemRequest)){
            return ResponseEntity
                    .badRequest()
                    .body("product out of stock or user not found");

        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    @DeleteMapping("/items/{productId}")
    public ResponseEntity<String> removeFromCart(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable Long productId
    ){
        boolean deleted = cartItemService.deleteItemFromCart(userId,productId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
    @GetMapping
    public ResponseEntity<List<CartItem>> getUsersCart(
            @RequestHeader("X-User-ID") String userId
    ){
        return new ResponseEntity<>(cartItemService.getCart(userId),HttpStatus.OK);
    }
}
