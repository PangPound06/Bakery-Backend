package com.app.my_project.repository;

import com.app.my_project.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {
    
    // ค้นหาแบบไม่สนใจตัวพิมพ์เล็ก/ใหญ่
    @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);
    
    // ค้นหาแบบปกติ
    Optional<UserEntity> findByEmail(String email);
    
    // ตรวจสอบว่า email มีอยู่หรือไม่
    boolean existsByEmail(String email);

    // ค้นหาผู้ใช้โดยใช้ Google ID
    Optional<UserEntity> findByGoogleId(String googleId);
}