package com.app.my_project.repository;

import com.app.my_project.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<ProductEntity, Long> {
    
    // ไม่ต้องเพิ่ม method อะไรพิเศษ เพราะเราใช้แค่ findById และ save เพื่อตัดสต็อก
}