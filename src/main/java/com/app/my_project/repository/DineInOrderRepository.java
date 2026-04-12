package com.app.my_project.repository;

import com.app.my_project.entity.DineInOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface DineInOrderRepository extends JpaRepository<DineInOrderEntity, Long> {
    List<DineInOrderEntity> findByEmailOrderByCreatedAtDesc(String email);
    List<DineInOrderEntity> findAllByOrderByCreatedAtDesc();

    // filter ตาม email + tableNo + เฉพาะหลังเวลาที่กำหนด
    List<DineInOrderEntity> findByEmailAndTableNoAndCreatedAtAfterOrderByCreatedAtDesc(
        String email, String tableNo, LocalDateTime after);
}