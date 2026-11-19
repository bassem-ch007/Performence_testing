package com.iyedbassem.ecommercemono.services.implementation;


import com.iyedbassem.ecommercemono.dtos.OrderItemDTO;
import com.iyedbassem.ecommercemono.dtos.OrderResponse;
import com.iyedbassem.ecommercemono.entities.CartItem;
import com.iyedbassem.ecommercemono.entities.Order;
import com.iyedbassem.ecommercemono.entities.OrderItem;
import com.iyedbassem.ecommercemono.entities.User;
import com.iyedbassem.ecommercemono.enums.OrderStatus;
import com.iyedbassem.ecommercemono.reposotories.OrderRepository;
import com.iyedbassem.ecommercemono.reposotories.UserRepository;
import com.iyedbassem.ecommercemono.services.CartItemService;
import com.iyedbassem.ecommercemono.services.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderServiceImplementation implements OrderService {
    private final CartItemService cartItemService;
    private final UserRepository userRepo;
    private final OrderRepository orderRepo;
    @Override
    @Transactional
    public Optional<OrderResponse> createOrder(String userId) {
        // Validate for Cart Item
        List<CartItem> cartItems = cartItemService.getCart(userId);
        if (cartItems.isEmpty()){
            return Optional.empty();
        }
        // Validate for User
        Optional<User> userOpt = userRepo.findById(Long.parseLong(userId));
        if (userOpt.isEmpty()){
            return Optional.empty();
        }
        User user = userOpt.get();
        // Calculate the total price
        BigDecimal totalPrice = cartItems.stream()
                .map(CartItem::getPrice)
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        // Create Order
        Order order = new Order();
        order.setUser(user);
        order.setTotalAmount(totalPrice);
        order.setOrderStatus(OrderStatus.CONFIRMED);
        List<OrderItem> orderItems = cartItems.stream()
                .map(cartItem -> new OrderItem(
                        null,
                        cartItem.getProduct(),
                        cartItem.getQuantity(),
                        cartItem.getPrice(),
                        order
                ))
                .toList();
        order.setItems(orderItems);
        Order savedOrder = orderRepo.save(order);
        // Clean the Cart
        cartItemService.cleanCart(userId);
        return Optional.of(mapToOrderResponse(savedOrder));
    }

    private OrderResponse mapToOrderResponse(Order savedOrder) {
        return new OrderResponse(
                savedOrder.getId(),
                savedOrder.getTotalAmount(),
                savedOrder.getOrderStatus(),
                savedOrder.getItems().stream()
                        .map(item -> new OrderItemDTO(
                                item.getId(),
                                item.getProduct().getId(),
                                item.getQuantity(),
                                item.getPrice(),
                                item.getPrice().multiply(new BigDecimal(item.getQuantity()))
                        )).toList(),
                savedOrder.getCreatedAt()
        );
    }
}
