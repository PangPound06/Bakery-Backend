package com.app.my_project.service;

import com.app.my_project.entity.CategoryEntity;
import com.app.my_project.entity.ProductEntity;
import com.app.my_project.models.ProductModel;
import com.app.my_project.repository.CategoryRepository;
import com.app.my_project.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * ProductService — business logic แยกออกจาก Controller
 *
 * Refactor pattern:
 *  - ใช้ ProductRepository (JPA) แทน raw JDBC
 *  - JOIN กับ category ทำใน-memory (lookup categoryId → CategoryEntity)
 *    เพราะ product จำนวนไม่เยอะ และง่ายต่อการ test
 *  - Helper method สำหรับ map Entity → Model มี logic เดียว
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    // ─── Public API ────────────────────────────────────────────────

    public List<ProductModel> getAll() {
        return productRepository.findAllByOrderByIdAsc().stream()
                .map(this::toModel)
                .toList();
    }

    public Optional<ProductModel> getById(Long id) {
        return productRepository.findById(id).map(this::toModel);
    }

    public List<ProductModel> getByCategory(String slug) {
        return productRepository.findByCategorySlugIgnoreCase(slug).stream()
                .map(this::toModel)
                .toList();
    }

    /**
     * สร้าง product ใหม่
     * @return ID ของ product ที่สร้าง
     * @throws IllegalArgumentException ถ้า name ว่าง
     */
    public Long create(CreateProductRequest req) {
        validateName(req.name());

        ProductEntity entity = new ProductEntity();
        applyToEntity(entity, req);

        // resolve categoryId จาก slug ถ้าไม่ได้ส่ง categoryId มา
        if (entity.getCategoryId() == null && req.category() != null) {
            resolveCategoryId(req.category()).ifPresent(entity::setCategoryId);
        }

        return productRepository.save(entity).getId();
    }

    /**
     * แก้ไข product
     * @return true ถ้าแก้สำเร็จ, false ถ้าไม่พบ
     */
    public boolean update(Long id, CreateProductRequest req) {
        Optional<ProductEntity> opt = productRepository.findById(id);
        if (opt.isEmpty()) return false;

        ProductEntity entity = opt.get();
        applyToEntity(entity, req);

        if (entity.getCategoryId() == null && req.category() != null) {
            resolveCategoryId(req.category()).ifPresent(entity::setCategoryId);
        }

        productRepository.save(entity);
        return true;
    }

    /**
     * ลบ product
     * @return true ถ้าลบสำเร็จ, false ถ้าไม่พบ
     */
    public boolean delete(Long id) {
        if (!productRepository.existsById(id)) return false;
        productRepository.deleteById(id);
        return true;
    }

    // ─── Helpers (package-private สำหรับ test ง่าย) ─────────────────

    /**
     * Map ProductEntity → ProductModel พร้อมข้อมูล category
     * Public for testing — helps verify mapping logic
     */
    ProductModel toModel(ProductEntity entity) {
        String catSlug = entity.getCategory();
        String catName = null;
        String catIcon = null;

        if (entity.getCategoryId() != null) {
            Optional<CategoryEntity> cat = categoryRepository.findById(entity.getCategoryId());
            if (cat.isPresent()) {
                catName = cat.get().getName();
                catIcon = cat.get().getIcon();
                // ถ้า slug ใน product ว่าง ใช้ของ category
                if (catSlug == null || catSlug.isEmpty()) {
                    catSlug = cat.get().getSlug();
                }
            }
        }

        return new ProductModel(
                entity.getId(),
                entity.getName(),
                entity.getPrice(),
                catSlug,
                entity.getCategoryId(),
                catName,
                catIcon,
                entity.getImage(),
                entity.getType(),
                entity.getDescription(),
                entity.getStockQuantity(),
                entity.getIsAvailable(),
                entity.getOptions()
        );
    }

    private void validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("กรุณากรอกชื่อสินค้า");
        }
    }

    private void applyToEntity(ProductEntity entity, CreateProductRequest req) {
        entity.setName(req.name());
        entity.setPrice(req.price() != null ? req.price() : 0.0);
        entity.setCategory(req.category() != null ? req.category() : "other");
        if (req.categoryId() != null) entity.setCategoryId(req.categoryId());
        entity.setImage(req.image() != null ? req.image() : "");
        entity.setType(req.type() != null ? req.type() : "");
        entity.setDescription(req.description() != null ? req.description() : "");
        entity.setStockQuantity(req.stockQuantity() != null ? req.stockQuantity() : 0L);
        entity.setIsAvailable(req.isAvailable() != null ? req.isAvailable() : true);
        entity.setOptions(req.options());
    }

    private Optional<Long> resolveCategoryId(String slug) {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getSlug() != null && c.getSlug().equalsIgnoreCase(slug))
                .findFirst()
                .map(CategoryEntity::getId);
    }

    /**
     * DTO สำหรับ create/update — record เพื่อความ immutable
     */
    public record CreateProductRequest(
            String name,
            Double price,
            String category,
            Long categoryId,
            String image,
            String type,
            String description,
            Long stockQuantity,
            Boolean isAvailable,
            String options
    ) {}
}