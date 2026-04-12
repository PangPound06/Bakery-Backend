package com.app.my_project.repository;

import com.app.my_project.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ProductRepository extends JpaRepository<ProductEntity, Long> {

    @Modifying
    @Transactional
    @Query(value = "UPDATE tb_products SET category = :newSlug WHERE category = :oldSlug", nativeQuery = true)
    void updateCategorySlug(@Param("oldSlug") String oldSlug, @Param("newSlug") String newSlug);
}