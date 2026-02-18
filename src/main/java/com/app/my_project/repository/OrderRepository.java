package com.app.my_project.repository;

import com.app.my_project.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    
    // ✅ ใช้ email แทน userId
    List<OrderEntity> findByEmailOrderByCreatedAtDesc(String email);
    
    List<OrderEntity> findAllByOrderByCreatedAtDesc();
    
    List<OrderEntity> findByOrderStatus(String orderStatus);
    
    List<OrderEntity> findByPaymentStatus(String paymentStatus);
}
