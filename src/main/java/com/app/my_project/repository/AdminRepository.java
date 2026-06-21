package com.app.my_project.repository;

import com.app.my_project.entity.AdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdminRepository
        extends JpaRepository<AdminEntity, Long>, JpaSpecificationExecutor<AdminEntity> {

    // ค้นหาแบบไม่สนใจตัวพิมพ์เล็ก/ใหญ่
    @Query("SELECT a FROM AdminEntity a WHERE LOWER(a.email) = LOWER(:email)")
    Optional<AdminEntity> findByEmailIgnoreCase(@Param("email") String email);

    // ค้นหาแบบปกติ
    Optional<AdminEntity> findByEmail(String email);

    // ตรวจสอบว่า email มีอยู่หรือไม่
    boolean existsByEmail(String email);

    // ค้นหาตาม role
    List<AdminEntity> findByRole(String role);

    // ✅ นับจำนวนตามบทบาท (สำหรับ badge บนแท็บ) — null role ถือเป็น 'admin' ให้ตรงกับ buildAdminData
    @Query("SELECT LOWER(COALESCE(a.role, 'admin')), COUNT(a) FROM AdminEntity a " +
           "GROUP BY LOWER(COALESCE(a.role, 'admin'))")
    List<Object[]> countGroupedByRole();

    // หมายเหตุ: การกรอง role/search + แบ่งหน้า ย้ายไปใช้ JpaSpecificationExecutor.findAll(spec, pageable)
    //          เพื่อเลี่ยงปัญหา ":param IS NULL" กับ null parameter บน PostgreSQL
}