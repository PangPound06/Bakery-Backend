package com.app.my_project.controller;

import com.app.my_project.entity.CategoryEntity;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test CategoryController
 *
 * Focus:
 *  - Slug generation จาก name (lowercase + replace ที่ไม่ใช่ a-z0-9 เป็น -)
 *  - Duplicate slug check
 *  - displayOrder = max + 1
 *  - Update: ถ้า slug เปลี่ยน → ต้อง update product ด้วย
 *  - Delete: ถ้ามีสินค้าผูก → error message
 */
@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks
    private CategoryController controller;

    private CategoryEntity sampleCategory;

    @BeforeEach
    void setUp() {
        sampleCategory = new CategoryEntity();
        sampleCategory.setId(1L);
        sampleCategory.setName("เค้ก");
        sampleCategory.setSlug("cakes");
        sampleCategory.setIcon("🎂");
        sampleCategory.setIsActive(true);
        sampleCategory.setDisplayOrder(1);
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/categories")
    class GetAllTests {

        @Test
        @DisplayName("คืน categories ทั้งหมด เรียงตาม displayOrder")
        void getAll_returnsListOrderedByDisplay() {
            when(categoryRepository.findAllByOrderByDisplayOrderAsc())
                    .thenReturn(List.of(sampleCategory));

            ResponseEntity<?> response = controller.getAll();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(categoryRepository).findAllByOrderByDisplayOrderAsc();
        }

        @Test
        @DisplayName("getActive() คืนเฉพาะที่ isActive=true")
        void getActive_returnsActiveOnly() {
            when(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc())
                    .thenReturn(List.of(sampleCategory));

            ResponseEntity<?> response = controller.getActive();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(categoryRepository).findByIsActiveTrueOrderByDisplayOrderAsc();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/categories — slug generation")
    class CreateTests {

        @Test
        @DisplayName("✅ name='Chocolate Cake' → slug='chocolate-cake'")
        void create_generatesCorrectSlug() {
            when(categoryRepository.existsBySlug(anyString())).thenReturn(false);
            when(categoryRepository.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
            when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> req = Map.of("name", "Chocolate Cake", "icon", "🍫");

            ResponseEntity<?> response = controller.create(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
            verify(categoryRepository).save(captor.capture());
            CategoryEntity saved = captor.getValue();
            assertThat(saved.getSlug()).isEqualTo("chocolate-cake");
            assertThat(saved.getName()).isEqualTo("Chocolate Cake");
            assertThat(saved.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("✅ name มีอักขระพิเศษ → replace เป็น '-'")
        void create_specialCharsInName_replacedWithDash() {
            when(categoryRepository.existsBySlug(anyString())).thenReturn(false);
            when(categoryRepository.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
            when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            controller.create(Map.of("name", "Bread & Pastry!"));

            ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
            verify(categoryRepository).save(captor.capture());
            // & และ ! ควรกลายเป็น -
            assertThat(captor.getValue().getSlug()).isEqualTo("bread---pastry-");
        }

        @Test
        @DisplayName("✅ displayOrder = max(existing) + 1")
        void create_displayOrderIsMaxPlusOne() {
            // มีของเดิม displayOrder = 3, 1, 5
            CategoryEntity c1 = new CategoryEntity(); c1.setDisplayOrder(3);
            CategoryEntity c2 = new CategoryEntity(); c2.setDisplayOrder(1);
            CategoryEntity c3 = new CategoryEntity(); c3.setDisplayOrder(5);

            when(categoryRepository.existsBySlug(anyString())).thenReturn(false);
            when(categoryRepository.findAllByOrderByDisplayOrderAsc())
                    .thenReturn(List.of(c1, c2, c3));
            when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            controller.create(Map.of("name", "New"));

            ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
            verify(categoryRepository).save(captor.capture());
            assertThat(captor.getValue().getDisplayOrder()).isEqualTo(6); // max(5) + 1
        }

        @Test
        @DisplayName("Name ว่าง → 400")
        void create_emptyName_returns400() {
            ResponseEntity<?> response = controller.create(Map.of("name", "  "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Slug ซ้ำ → 400 'หมวดหมู่นี้มีอยู่แล้ว'")
        void create_duplicateSlug_returns400() {
            when(categoryRepository.existsBySlug("cakes")).thenReturn(true);

            ResponseEntity<?> response = controller.create(Map.of("name", "Cakes"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("มีอยู่แล้ว");
            verify(categoryRepository, never()).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/categories/{id} — slug propagation")
    class UpdateTests {

        @Test
        @DisplayName("✅ เปลี่ยน name → slug ใหม่ + product table ก็ update slug ตาม")
        void update_nameChange_propagatesNewSlugToProducts() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));
            when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> req = new HashMap<>();
            req.put("name", "Premium Cakes"); // slug จะกลายเป็น premium-cakes

            ResponseEntity<?> response = controller.update(1L, req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // ตรวจว่า product table ก็ถูก migrate slug ด้วย
            verify(productRepository).updateCategorySlug("cakes", "premium-cakes");
        }

        @Test
        @DisplayName("ไม่เปลี่ยน name → ไม่เรียก updateCategorySlug")
        void update_noNameChange_noProductMigration() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));
            when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            controller.update(1L, Map.of("icon", "🎉"));

            verify(productRepository, never()).updateCategorySlug(anyString(), anyString());
        }

        @Test
        @DisplayName("ไม่พบ category → 400")
        void update_notFound_returns400() {
            when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.update(999L, Map.of("name", "X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Partial update: ส่ง isActive=false → เปลี่ยนแค่ status")
        void update_isActiveOnly_keepsOthers() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(sampleCategory));
            when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> req = new HashMap<>();
            req.put("isActive", false);

            controller.update(1L, req);

            ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
            verify(categoryRepository).save(captor.capture());
            assertThat(captor.getValue().getIsActive()).isFalse();
            // name คงเดิม
            assertThat(captor.getValue().getName()).isEqualTo("เค้ก");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /api/categories/{id}")
    class DeleteTests {

        @Test
        @DisplayName("ลบสำเร็จ → 200")
        void delete_success_returns200() {
            when(categoryRepository.existsById(1L)).thenReturn(true);
            doNothing().when(categoryRepository).deleteById(1L);

            ResponseEntity<?> response = controller.delete(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(castBody(response)).containsEntry("success", true);
        }

        @Test
        @DisplayName("ไม่พบ category → 400")
        void delete_notFound_returns400() {
            when(categoryRepository.existsById(999L)).thenReturn(false);

            ResponseEntity<?> response = controller.delete(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(categoryRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("✅ ลบไม่ได้เพราะมีสินค้าผูก (FK constraint) → 400 + message")
        void delete_hasProducts_returns400WithMessage() {
            when(categoryRepository.existsById(1L)).thenReturn(true);
            doThrow(new DataIntegrityViolationException("FK violation"))
                    .when(categoryRepository).deleteById(1L);

            ResponseEntity<?> response = controller.delete(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("สินค้า");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}