package com.iyedbassem.productservice.controllers;

import com.iyedbassem.productservice.dtos.ProductRequest;
import com.iyedbassem.productservice.dtos.ProductResponse;
import com.iyedbassem.productservice.services.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        return new ResponseEntity<>(productService.fetchAllProducts(), HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<String> addProduct(@RequestBody ProductRequest productRequest) {
        productService.addProduct(productRequest);
        return new ResponseEntity<>("Product Added Successfully", HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return productService.getProductByID(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<String> updateProduct(@PathVariable Long id, @RequestBody ProductRequest productRequest) {
        Boolean update = productService.update(id, productRequest);
        if (update) {
            return new ResponseEntity<>("The Product is updated Successfully", HttpStatus.OK);
        }
        return new ResponseEntity<>("The Product is not Found", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteProduct(@PathVariable Long id) {
        Boolean deleted = productService.deleteProduct(id);
        return deleted ? new ResponseEntity<>("Product Deleted", HttpStatus.NO_CONTENT) : new ResponseEntity<>("The Product is not found", HttpStatus.NOT_FOUND);
    }

    @GetMapping("/search")
    public ResponseEntity<List<ProductResponse>> searchProduct(@RequestParam String keyword) {
        return ResponseEntity.ok(productService.filterByName(keyword));
    }
}
