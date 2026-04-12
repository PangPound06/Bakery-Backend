package com.app.my_project.repository;

import com.app.my_project.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> {
    List<CategoryEntity> findAllByOrderByDisplayOrderAsc();
    List<CategoryEntity> findByIsActiveTrueOrderByDisplayOrderAsc();
    Optional<CategoryEntity> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsByName(String name);
}