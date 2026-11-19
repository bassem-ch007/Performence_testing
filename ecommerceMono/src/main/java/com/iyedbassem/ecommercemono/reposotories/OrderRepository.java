package com.iyedbassem.ecommercemono.reposotories;

import com.iyedbassem.ecommercemono.entities.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order,Long> {
}
