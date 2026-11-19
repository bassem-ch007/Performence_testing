package com.iyedbassem.ecommercemono.dtos;

import com.iyedbassem.ecommercemono.entities.Product;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemResponse {
    private Product product;
    private Integer quantity;
    private BigDecimal price;
}
