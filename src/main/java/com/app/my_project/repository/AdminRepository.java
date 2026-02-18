package com.app.my_project.repository;

import com.app.my_project.entity.AdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminRepository extends JpaRepository<AdminEntity, Long> {
    
    // ค้นหาแบบไม่สนใจตัวพิมพ์เล็ก/ใหญ่
    @Query("SELECT a FROM AdminEntity a WHERE LOWER(a.email) = LOWER(:email)")
    Optional<AdminEntity> findByEmailIgnoreCase(@Param("email") String email);
    
    // ค้นหาแบบปกติ
    Optional<AdminEntity> findByEmail(String email);
    
    // ตรวจสอบว่า email มีอยู่หรือไม่
    boolean existsByEmail(String email);
    
    // ค้นหาตาม role
    List<AdminEntity> findByRole(String role);
    
}
