package com.app.my_project.service;

import com.app.my_project.entity.CategoryEntity;
import com.app.my_project.entity.ProductEntity;
import com.app.my_project.models.ProductModel;
import com.app.my_project.repository.CategoryRepository;
import com.app.my_project.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test ProductService
 *
 * Focus:
 *  - getAll/getById/getByCategory — basic queries
 *  - create — validation, category resolution, default values
 *  - update — partial update, missing categoryId handling
 *  - delete — existsById check
 *  - toModel — JOIN logic in-memory + slug fallback
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    private ProductEntity sampleProduct;
    private CategoryEntity sampleCategory;

    @BeforeEach
    void setUp() {
        sampleCategory = new CategoryEntity();
        sampleCategory.setId(1L);
        sampleCategory.setName("เค้ก");
        sampleCategory.setSlug("cakes");
        sampleCategory.setIcon("🎂");

        sampleProduct = new ProductEntity();
        sampleProduct.setId(10L);
        sampleProduct.setName("ช็อกโกแลตเค้ก");
        sampleProduct.setPrice(250.0);
        sampleProduct.setCategory("cakes");
        sampleProduct.setCategoryId(1L);
        sampleProduct.setStockQuantity(100L);
        sampleProduct.setIsAvailable(true);
    }

    private ProductService.CreateProductRequest validRequest() {
        return new ProductService.CreateProductRequest(
                "New Cake", 200.0, "cakes", 1L,
                "img.jpg", "เค้ก", "delicious", 50L, true, "{}"
        );
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getAll() / getById() / getByCategory()")
    class GetTests {

        @Test
        @DisplayName("getAll → list ทั้งหมด เรียงตาม id")
        void getAll_returnsAllAsModels() {
            when(productRepository.findAllByOrderByIdAsc())
                    .thenReturn(List.of(sampleProduct));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));

            List<ProductModel> result = productService.getAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("ช็อกโกแลตเค้ก");
            assertThat(result.get(0).getCategoryName()).isEqualTo("เค้ก"); // join ทำงาน
        }

        @Test
        @DisplayName("getById มี → return Model")
        void getById_existing_returnsModel() {
            when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));

            Optional<ProductModel> result = productService.getById(10L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("getById ไม่มี → empty")
        void getById_notFound_empty() {
            when(productRepository.findById(999L)).thenReturn(Optional.empty());

            Optional<ProductModel> result = productService.getById(999L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getByCategory case-insensitive (CAKES = cakes)")
        void getByCategory_callsRepositoryIgnoreCase() {
            when(productRepository.findByCategorySlugIgnoreCase("CAKES"))
                    .thenReturn(List.of(sampleProduct));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));

            List<ProductModel> result = productService.getByCategory("CAKES");

            assertThat(result).hasSize(1);
            verify(productRepository).findByCategorySlugIgnoreCase("CAKES");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("Happy path: สร้างสำเร็จ → คืน id ใหม่")
        void create_validRequest_returnsId() {
            when(productRepository.save(any())).thenAnswer(inv -> {
                ProductEntity e = inv.getArgument(0);
                e.setId(99L);
                return e;
            });

            Long id = productService.create(validRequest());

            assertThat(id).isEqualTo(99L);

            ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(captor.capture());
            ProductEntity saved = captor.getValue();
            assertThat(saved.getName()).isEqualTo("New Cake");
            assertThat(saved.getPrice()).isEqualTo(200.0);
            assertThat(saved.getCategoryId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("✅ Name ว่าง → throw IllegalArgumentException")
        void create_emptyName_throws() {
            ProductService.CreateProductRequest bad = new ProductService.CreateProductRequest(
                    "  ", 100.0, "cakes", 1L, null, null, null, 1L, true, null
            );

            assertThatThrownBy(() -> productService.create(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ชื่อสินค้า");

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("Name = null → throw IllegalArgumentException")
        void create_nullName_throws() {
            ProductService.CreateProductRequest bad = new ProductService.CreateProductRequest(
                    null, 100.0, "cakes", null, null, null, null, null, null, null
            );

            assertThatThrownBy(() -> productService.create(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ ไม่ส่ง categoryId แต่ส่ง category slug → resolve เป็น id อัตโนมัติ")
        void create_categorySlugOnly_resolvesId() {
            ProductService.CreateProductRequest req = new ProductService.CreateProductRequest(
                    "New Cake", 200.0, "cakes", null, // ← ไม่มี categoryId
                    null, null, null, 10L, true, null
            );

            when(categoryRepository.findAll()).thenReturn(List.of(sampleCategory));
            when(productRepository.save(any())).thenAnswer(inv -> {
                ProductEntity e = inv.getArgument(0);
                e.setId(1L);
                return e;
            });

            productService.create(req);

            ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(captor.capture());
            // categoryId ถูก resolve เป็น 1 จาก slug "cakes"
            assertThat(captor.getValue().getCategoryId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("✅ Default values: price=0, stockQuantity=0, isAvailable=true, category='other'")
        void create_nullFields_appliesDefaults() {
            ProductService.CreateProductRequest req = new ProductService.CreateProductRequest(
                    "Bare Product", null, null, null, null, null, null, null, null, null
            );

            when(productRepository.save(any())).thenAnswer(inv -> {
                ProductEntity e = inv.getArgument(0);
                e.setId(1L);
                return e;
            });

            productService.create(req);

            ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(captor.capture());
            ProductEntity saved = captor.getValue();
            assertThat(saved.getPrice()).isEqualTo(0.0);
            assertThat(saved.getCategory()).isEqualTo("other");
            assertThat(saved.getStockQuantity()).isEqualTo(0L);
            assertThat(saved.getIsAvailable()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("✅ Update มีอยู่ → true + save with new values")
        void update_existing_returnsTrue() {
            when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
            when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ProductService.CreateProductRequest req = new ProductService.CreateProductRequest(
                    "Updated Name", 999.0, "cakes", 1L, null, null, null, 50L, false, null
            );

            boolean result = productService.update(10L, req);

            assertThat(result).isTrue();

            ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Updated Name");
            assertThat(captor.getValue().getPrice()).isEqualTo(999.0);
            assertThat(captor.getValue().getIsAvailable()).isFalse();
        }

        @Test
        @DisplayName("✅ Update ไม่มี → false + ไม่ save")
        void update_notFound_returnsFalse() {
            when(productRepository.findById(999L)).thenReturn(Optional.empty());

            boolean result = productService.update(999L, validRequest());

            assertThat(result).isFalse();
            verify(productRepository, never()).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("มีอยู่ → ลบ + return true")
        void delete_existing_returnsTrue() {
            when(productRepository.existsById(10L)).thenReturn(true);

            boolean result = productService.delete(10L);

            assertThat(result).isTrue();
            verify(productRepository).deleteById(10L);
        }

        @Test
        @DisplayName("ไม่มี → return false + ไม่ลบ")
        void delete_notFound_returnsFalse() {
            when(productRepository.existsById(999L)).thenReturn(false);

            boolean result = productService.delete(999L);

            assertThat(result).isFalse();
            verify(productRepository, never()).deleteById(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toModel() — JOIN logic")
    class ToModelTests {

        @Test
        @DisplayName("✅ Product มี categoryId → JOIN ดึง categoryName + icon")
        void toModel_withCategory_joinsData() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));

            ProductModel model = productService.toModel(sampleProduct);

            assertThat(model.getCategoryName()).isEqualTo("เค้ก");
            assertThat(model.getCategoryIcon()).isEqualTo("🎂");
            assertThat(model.getCategoryId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Product ไม่มี categoryId → categoryName/icon = null")
        void toModel_noCategoryId_nullJoinFields() {
            sampleProduct.setCategoryId(null);

            ProductModel model = productService.toModel(sampleProduct);

            assertThat(model.getCategoryName()).isNull();
            assertThat(model.getCategoryIcon()).isNull();
            verify(categoryRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Product มี categoryId แต่ category ถูกลบไปแล้ว → ไม่ crash, fields = null")
        void toModel_orphanedCategoryId_handlesGracefully() {
            sampleProduct.setCategoryId(999L);
            when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

            ProductModel model = productService.toModel(sampleProduct);

            assertThat(model.getCategoryName()).isNull();
            assertThat(model.getCategoryIcon()).isNull();
        }

        @Test
        @DisplayName("✅ Slug ใน product ว่าง → fallback ใช้ slug ของ category")
        void toModel_emptyProductSlug_usesCategorySlug() {
            sampleProduct.setCategory(null); // slug ว่าง
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));

            ProductModel model = productService.toModel(sampleProduct);

            assertThat(model.getCategory()).isEqualTo("cakes"); // จาก category
        }
    }
}