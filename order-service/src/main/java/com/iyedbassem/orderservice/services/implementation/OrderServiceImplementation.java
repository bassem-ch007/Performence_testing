package com.iyedbassem.orderservice.services.implementation;

import com.iyedbassem.orderservice.dtos.OrderItemDTO;
import com.iyedbassem.orderservice.dtos.OrderResponse;
import com.iyedbassem.orderservice.entities.CartItem;
import com.iyedbassem.orderservice.entities.Order;
import com.iyedbassem.orderservice.entities.OrderItem;
import com.iyedbassem.orderservice.enums.OrderStatus;
import com.iyedbassem.orderservice.grpc.client.UserClient;
import com.iyedbassem.orderservice.repositories.OrderRepository;
import com.iyedbassem.orderservice.services.CartItemService;
import com.iyedbassem.orderservice.services.OrderService;
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
    private final UserClient userClient;
    private final OrderRepository orderRepo;

    @Override
    @Transactional
    public Optional<OrderResponse> createOrder(String userId) {
        // Validate for Cart Item
        List<CartItem> cartItems = cartItemService.getCart(userId);
        if (cartItems.isEmpty()) {
            return Optional.empty();
        }
        // Validate for User (user-service over gRPC)
        Long uid = Long.parseLong(userId);
        if (userClient.findById(uid).isEmpty()) {
            return Optional.empty();
        }
        // Calculate the total price
        BigDecimal totalPrice = cartItems.stream()
                .map(CartItem::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Create Order
        Order order = new Order();
        order.setUserId(uid);
        order.setTotalAmount(totalPrice);
        order.setOrderStatus(OrderStatus.CONFIRMED);
        List<OrderItem> orderItems = cartItems.stream()
                .map(cartItem -> new OrderItem(
                        null,
                        cartItem.getProductId(),
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

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderResponse> getOrder(Long orderId) {
        return orderRepo.findWithItemsById(orderId).map(this::mapToOrderResponse);
    }

    private OrderResponse mapToOrderResponse(Order savedOrder) {
        return new OrderResponse(
                savedOrder.getId(),
                savedOrder.getTotalAmount(),
                savedOrder.getOrderStatus(),
                savedOrder.getItems().stream()
                        .map(item -> new OrderItemDTO(
                                item.getId(),
                                item.getProductId(),
                                item.getQuantity(),
                                item.getPrice(),
                                item.getPrice().multiply(new BigDecimal(item.getQuantity()))
                        )).toList(),
                savedOrder.getCreatedAt()
        );
    }
}
