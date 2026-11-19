package com.iyedbassem.productservice.services.implementation;

import com.iyedbassem.productservice.dtos.ProductRequest;
import com.iyedbassem.productservice.dtos.ProductResponse;
import com.iyedbassem.productservice.entities.Product;
import com.iyedbassem.productservice.repositories.ProductRepository;
import com.iyedbassem.productservice.services.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductServiceImplementation implements ProductService {
    private final ProductRepository productRepo;

    @Override
    public List<ProductResponse> fetchAllProducts() {
        return productRepo.findByActiveTrue().stream()
                .map(this::mapProductToProductResponse)
                .toList();
    }

    @Override
    public Optional<ProductResponse> getProductByID(Long id) {
        return productRepo.findById(id).map(this::mapProductToProductResponse);
    }

    @Override
    public List<ProductResponse> filterByName(String name) {
        return productRepo.searchProduct(name)
                .stream()
                .map(this::mapProductToProductResponse)
                .toList();
    }

    @Override
    public void addProduct(ProductRequest productRequest) {
        Product product = new Product();
        updateProductFromRequest(product, productRequest);
        productRepo.save(product);
    }

    @Override
    public Boolean update(Long id, ProductRequest productRequest) {
        return productRepo.findById(id)
                .map(existingProduct -> {
                    updateProductFromRequest(existingProduct, productRequest);
                    productRepo.save(existingProduct);
                    return true;
                }).orElse(false);
    }

    @Override
    public Boolean deleteProduct(Long id) {
        return productRepo.findById(id)
                .map(product -> {
                    product.setActive(false);
                    productRepo.save(product);
                    return true;
                }).orElse(false);
    }

    private void updateProductFromRequest(Product product, ProductRequest productRequest) {
        product.setName(productRequest.getName());
        product.setDescription(productRequest.getDescription());
        product.setPrice(productRequest.getPrice());
        product.setStockQuantity(productRequest.getStockQuantity());
        product.setCategory(productRequest.getCategory());
        product.setImageUrl(productRequest.getImageUrl());
    }

    private ProductResponse mapProductToProductResponse(Product product) {
        ProductResponse productResponse = new ProductResponse();
        productResponse.setId(String.valueOf(product.getId()));
        productResponse.setName(product.getName());
        productResponse.setDescription(product.getDescription());
        productResponse.setActive(product.getActive());
        productResponse.setCategory(product.getCategory());
        productResponse.setPrice(product.getPrice());
        productResponse.setImageUrl(product.getImageUrl());
        productResponse.setStockQuantity(product.getStockQuantity());
        return productResponse;
    }
}
