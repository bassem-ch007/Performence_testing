package com.iyedbassem.ecommercemono.services;

import com.iyedbassem.ecommercemono.dtos.ProductRequest;
import com.iyedbassem.ecommercemono.dtos.ProductResponse;

import java.util.List;
import java.util.Optional;

public interface ProductService {
    List<ProductResponse> fetchAllProducts();
    Optional<ProductResponse> getProductByID(Long id);
    List<ProductResponse> filterByName(String name);
    void addProduct(ProductRequest productRequest);
    Boolean update(Long id,ProductRequest productRequest);
    Boolean deleteProduct(Long id);
}
