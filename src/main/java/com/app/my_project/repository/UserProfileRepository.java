package com.app.my_project.repository;

import com.app.my_project.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, Long> {
    Optional<UserProfileEntity> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
    Optional<UserProfileEntity> findByEmail(String email);
    void deleteByUserId(Long userId);
}
