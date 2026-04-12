package com.app.my_project.repository;

import com.app.my_project.entity.PurchaseOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, Long> {

    List<PurchaseOrderEntity> findBySupplierId(Long supplierId);

    List<PurchaseOrderEntity> findByStatus(String status);

    List<PurchaseOrderEntity> findByPaymentStatus(String paymentStatus);

    boolean existsByPoCode(String poCode);
}