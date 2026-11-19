package com.iyedbassem.ecommercemono.reposotories;


import com.iyedbassem.ecommercemono.entities.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product,Long> {
    List<Product> findByActiveTrue();
    @Query("select p from products p where p.active = true and p.stockQuantity>0 and lower(p.name) LIKE lower(concat('%',:keyword,'%') ) ")
    List<Product> searchProduct(@Param("keyword") String keyword);
}
