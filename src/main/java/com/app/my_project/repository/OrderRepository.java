package com.app.my_project.repository;

import com.app.my_project.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {

    // ✅ ใช้ email แทน userId
    List<OrderEntity> findByEmailOrderByCreatedAtDesc(String email);

    List<OrderEntity> findAllByOrderByCreatedAtDesc();

    List<OrderEntity> findByOrderStatus(String orderStatus);

    List<OrderEntity> findByPaymentStatus(String paymentStatus);

    Optional<OrderEntity> findByOrdCode(String ordCode);

    void deleteByEmail(String email);
}