package com.iyedbassem.ecommercemono.services.implementation;


import com.iyedbassem.ecommercemono.dtos.CartItemRequest;
import com.iyedbassem.ecommercemono.entities.CartItem;
import com.iyedbassem.ecommercemono.entities.Product;
import com.iyedbassem.ecommercemono.entities.User;
import com.iyedbassem.ecommercemono.reposotories.CartItemRepository;
import com.iyedbassem.ecommercemono.reposotories.ProductRepository;
import com.iyedbassem.ecommercemono.reposotories.UserRepository;
import com.iyedbassem.ecommercemono.services.CartItemService;
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
    private final ProductRepository productRepo;
    private final UserRepository userRepo;
    @Override
    public Boolean AddToCart(String user_id, CartItemRequest cartItemRequest) {
        Optional<Product> productOpt = productRepo.findById(cartItemRequest.getProductId());
        if (productOpt.isEmpty()){
            return Boolean.FALSE;
        }
        Product product = productOpt.get();
        if (product.getStockQuantity()<cartItemRequest.getQuantity()){
            return Boolean.FALSE;
        }
        // looking for the user
        Optional<User> userOpt = userRepo.findById(Long.parseLong(user_id));
        if (userOpt.isEmpty()){
            return Boolean.FALSE;
        }
        User user = userOpt.get();
        CartItem existingCartItem = cartItemRepo.findByUserAndProduct(user , product);
        if (existingCartItem != null){
            // update quantity
            existingCartItem.setQuantity(existingCartItem.getQuantity()+cartItemRequest.getQuantity());
            existingCartItem.setPrice(product.getPrice().multiply(BigDecimal.valueOf(existingCartItem.getQuantity())));
            cartItemRepo.save(existingCartItem);
        }else {
            // create a cart item
            CartItem cartItem = new CartItem();
            cartItem.setUser(user);
            cartItem.setProduct(product);
            cartItem.setQuantity(cartItemRequest.getQuantity());
            cartItem.setPrice(product.getPrice().multiply(BigDecimal.valueOf(cartItemRequest.getQuantity())));
            cartItemRepo.save(cartItem);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional
    public boolean deleteItemFromCart(String userId, Long productId) {
        Optional<Product> productOpt = productRepo.findById(productId);
        Optional<User> userOpt = userRepo.findById(Long.parseLong(userId));
        if (productOpt.isPresent() && userOpt.isPresent()){
            cartItemRepo.deleteByUserAndProduct(userOpt.get(),productOpt.get());
            return true;
        }
        return false;
    }

    @Override
    public List<CartItem> getCart(String userId) {
        return userRepo.findById(Long.parseLong(userId))
                .map(cartItemRepo::findByUser)
                .orElseGet(List::of);
    }

    @Override
    @Transactional
    public void cleanCart(String userId) {
        userRepo.findById(Long.parseLong(userId)).ifPresent(
                cartItemRepo::deleteByUser
        );
    }
}
