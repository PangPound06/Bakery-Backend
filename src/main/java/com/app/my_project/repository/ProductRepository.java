package com.app.my_project.repository;

import com.app.my_project.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ProductRepository — refactored to replace raw SQL in ProductController
 *
 * Custom methods แทน raw JDBC:
 *  - findAllOrderById — แทน "SELECT ... ORDER BY p.id ASC"
 *  - findByCategorySlug — แทน "WHERE LOWER(c.slug) = LOWER(?)"
 *  - findByIdWithDetails — query เดียวคืน product
 *
 * JOIN กับ tb_categories จะทำใน Service layer (เพราะ JPA cross-entity join ซับซ้อนกว่า)
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long> {

    /**
     * Existing — used by CategoryController.update() to propagate slug changes
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE tb_products SET category = :newSlug WHERE category = :oldSlug", nativeQuery = true)
    void updateCategorySlug(@Param("oldSlug") String oldSlug, @Param("newSlug") String newSlug);

    /**
     * Find all ordered by id ASC (เดิมใช้ raw SQL)
     */
    List<ProductEntity> findAllByOrderByIdAsc();

    /**
     * Find by category slug case-insensitive
     */
    @Query("SELECT p FROM ProductEntity p WHERE LOWER(p.category) = LOWER(:slug) ORDER BY p.id ASC")
    List<ProductEntity> findByCategorySlugIgnoreCase(@Param("slug") String slug);
}