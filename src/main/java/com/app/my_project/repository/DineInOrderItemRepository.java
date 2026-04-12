package com.app.my_project.repository;

import com.app.my_project.entity.DineInOrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DineInOrderItemRepository extends JpaRepository<DineInOrderItemEntity, Long> {
    List<DineInOrderItemEntity> findByOrderId(Long orderId);
}