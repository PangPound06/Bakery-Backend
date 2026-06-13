package com.app.my_project.repository;

import com.app.my_project.entity.StoreBranchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreBranchRepository extends JpaRepository<StoreBranchEntity, Long> {

    // เฉพาะสาขาที่เปิดใช้งาน (สำหรับหน้าลูกค้า) เรียงตาม sortOrder แล้ว id
    List<StoreBranchEntity> findByActiveTrueOrderBySortOrderAscIdAsc();

    // ทุกสาขา (สำหรับ admin)
    List<StoreBranchEntity> findAllByOrderBySortOrderAscIdAsc();
}