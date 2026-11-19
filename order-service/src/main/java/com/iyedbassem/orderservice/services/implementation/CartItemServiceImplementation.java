package com.iyedbassem.orderservice.services.implementation;

import com.iyedbassem.grpc.product.ProductReply;
import com.iyedbassem.orderservice.dtos.CartItemRequest;
import com.iyedbassem.orderservice.entities.CartItem;
import com.iyedbassem.orderservice.grpc.client.ProductClient;
import com.iyedbassem.orderservice.grpc.client.UserClient;
import com.iyedbassem.orderservice.repositories.CartItemRepository;
import com.iyedbassem.orderservice.services.CartItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CartItemServiceImplementation implements CartItemService {
    private final CartItemRepository cartItemRepo;
    private final ProductClient productClient;
    private final UserClient userClient;

    @Override
    public Boolean AddToCart(String userId, CartItemRequest cartItemRequest) {
        // looking for the product (product-service over gRPC)
        Optional<ProductReply> productOpt = productClient.findById(cartItemRequest.getProductId());
        if (productOpt.isEmpty()) {
            return Boolean.FALSE;
        }
        ProductReply product = productOpt.get();
        if (product.getStockQuantity() < cartItemRequest.getQuantity()) {
            return Boolean.FALSE;
        }
        // looking for the user (user-service over gRPC)
        Long uid = Long.parseLong(userId);
        if (userClient.findById(uid).isEmpty()) {
            return Boolean.FALSE;
        }
        BigDecimal unitPrice = new BigDecimal(product.getPrice());
        CartItem existingCartItem = cartItemRepo.findByUserIdAndProductId(uid, product.getId());
        if (existingCartItem != null) {
            // update quantity
            existingCartItem.setQuantity(existingCartItem.getQuantity() + cartItemRequest.getQuantity());
            existingCartItem.setPrice(unitPrice.multiply(BigDecimal.valueOf(existingCartItem.getQuantity())));
            cartItemRepo.save(existingCartItem);
        } else {
            // create a cart item
            CartItem cartItem = new CartItem();
            cartItem.setUserId(uid);
            cartItem.setProductId(product.getId());
            cartItem.setQuantity(cartItemRequest.getQuantity());
            cartItem.setPrice(unitPrice.multiply(BigDecimal.valueOf(cartItemRequest.getQuantity())));
            cartItemRepo.save(cartItem);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional
    public boolean deleteItemFromCart(String userId, Long productId) {
        Long uid = Long.parseLong(userId);
        if (productClient.findById(productId).isPresent() && userClient.findById(uid).isPresent()) {
            cartItemRepo.deleteByUserIdAndProductId(uid, productId);
            return true;
        }
        return false;
    }

    @Override
    public List<CartItem> getCart(String userId) {
        Long uid = Long.parseLong(userId);
        return userClient.findById(uid)
                .map(user -> cartItemRepo.findByUserId(uid))
                .orElseGet(List::of);
    }

    @Override
    @Transactional
    public void cleanCart(String userId) {
        Long uid = Long.parseLong(userId);
        userClient.findById(uid).ifPresent(user -> cartItemRepo.deleteByUserId(uid));
    }
}
