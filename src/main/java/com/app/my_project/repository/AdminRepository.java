package com.app.my_project.repository;

import com.app.my_project.entity.AdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

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

    // ✅ ค้นหา + กรองบทบาท + แบ่งหน้า (server-side)
    // role/search เป็น null 䓢ด้ = ไม่กรอง; null role ถือเป็น 'admin' 䏒ห้ตรงกับ buildAdminData
    @Query("SELECT a FROM AdminEntity a WHERE " +
           "(:role IS NULL OR LOWER(COALESCE(a.role, 'admin')) = LOWER(:role)) AND " +
           "(:search IS NULL OR LOWER(a.fullname) LIKE LOWER(CONCAT('%', :search, '%')) " +
           " OR LOWER(a.email) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<AdminEntity> search(@Param("role") String role,
                             @Param("search") String search,
                             Pageable pageable);

    // ✅ นับจำนวนตามบทบาท (สำหรับ badge บนแท็บ)
    @Query("SELECT LOWER(COALESCE(a.role, 'admin')), COUNT(a) FROM AdminEntity a " +
           "GROUP BY LOWER(COALESCE(a.role, 'admin'))")
    List<Object[]> countGroupedByRole();

}