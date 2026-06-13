package com.app.my_project.repository;

import com.app.my_project.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {
    
    List<OrderItemEntity> findByOrderId(Long orderId);

    List<OrderItemEntity> findByOrderIdIn(List<Long> orderIds);

    void deleteByOrderId(Long orderId);
}