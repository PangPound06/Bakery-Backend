package com.app.my_project.controller;

import com.app.my_project.controller.ProductController.ErrorResponse;
import com.app.my_project.controller.ProductController.SuccessResponse;
import com.app.my_project.models.ProductModel;
import com.app.my_project.models.ProductRequest;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test สำหรับ ProductController (หลัง refactor)
 * โฟกัส:
 *  - การตรวจ auth (มี/ไม่มี token) บน endpoint ที่ต้องล็อกอิน
 *  - การ validate input (ชื่อสินค้าว่าง)
 *  - การ map ผลลัพธ์จาก ProductService → HTTP status
 *  - การจัดการ error (RuntimeException → 500)
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private ProductController controller;

    private static final String VALID_AUTH = "Bearer valid-token";
    private static final String BAD_AUTH = "Bearer bad-token";

    private void mockAuthenticated() {
        when(jwtService.getUserIdFromHeader(VALID_AUTH)).thenReturn(1L);
    }

    private ProductModel makeProduct(Long id) {
        return new ProductModel(id, "ครัวซองต์", 45.0, "bakery", 2L,
                "เบเกอรี่", "🥐", "img.png", "single", "อร่อย", 10L, true, null);
    }

    private ProductRequest makeRequest(String name) {
        ProductRequest r = new ProductRequest();
        r.setName(name);
        r.setPrice(45.0);
        r.setCategory("bakery");
        return r;
    }

    private ErrorResponse asError(ResponseEntity<?> res) {
        assertThat(res.getBody()).isInstanceOf(ErrorResponse.class);
        return (ErrorResponse) res.getBody();
    }

    private SuccessResponse asSuccess(ResponseEntity<?> res) {
        assertThat(res.getBody()).isInstanceOf(SuccessResponse.class);
        return (SuccessResponse) res.getBody();
    }

    @Nested
    @DisplayName("GET /api/products")
    class GetAll {
        @Test
        @DisplayName("คืนรายการสินค้า → 200")
        void success() {
            when(productService.getAll()).thenReturn(List.of(makeProduct(1L), makeProduct(2L)));

            ResponseEntity<?> res = controller.getAllProducts();

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) res.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("service พัง→ 500")
        void serviceError() {
            when(productService.getAll()).thenThrow(new RuntimeException("db down"));

            ResponseEntity<?> res = controller.getAllProducts();

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(asError(res).getMessage()).contains("db down");
        }
    }

    @Nested
    @DisplayName("GET /api/products/{id}")
    class GetById {
        @Test
        @DisplayName("เจอสินค้า → 200")
        void found() {
            when(productService.getById(5L)).thenReturn(Optional.of(makeProduct(5L)));

            ResponseEntity<?> res = controller.getProductById(5L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isInstanceOf(ProductModel.class);
        }

        @Test
        @DisplayName("ไม่เจอสินค้า → 404")
        void notFound() {
            when(productService.getById(99L)).thenReturn(Optional.empty());

            ResponseEntity<?> res = controller.getProductById(99L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(asError(res).getMessage()).isEqualTo("ไม่พบสินค้า");
        }

        @Test
        @DisplayName("service พัง → 500")
        void serviceError() {
            when(productService.getById(5L)).thenThrow(new RuntimeException("boom"));

            ResponseEntity<?> res = controller.getProductById(5L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET /api/products/category/{category}")
    class GetByCategory {
        @Test
        @DisplayName("คืนรายการตามหมวด → 200")
        void success() {
            when(productService.getByCategory("bakery")).thenReturn(List.of(makeProduct(1L)));

            ResponseEntity<?> res = controller.getProductsByCategory("bakery");

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) res.getBody()).hasSize(1);
        }

        @Test
        @DisplayName("service พัง → 500")
        void serviceError() {
            when(productService.getByCategory("bakery")).thenThrow(new RuntimeException("x"));

            ResponseEntity<?> res = controller.getProductsByCategory("bakery");

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("POST /api/products")
    class Create {
        @Test
        @DisplayName("ไม่มี token → 401 และไม่เรียก service")
        void unauthorized() {
            ResponseEntity<?> res = controller.createProduct(BAD_AUTH, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(productService, never()).create(any());
        }

        @Test
        @DisplayName("ชื่อสินค้าเป็น null → 400")
        void nullName() {
            mockAuthenticated();

            ResponseEntity<?> res = controller.createProduct(VALID_AUTH, makeRequest(null));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(asError(res).getMessage()).isEqualTo("กรุณากรอกชื่อสินค้า");
            verify(productService, never()).create(any());
        }

        @Test
        @DisplayName("ชื่อสินค้าเป็นช่องว่าง → 400")
        void blankName() {
            mockAuthenticated();

            ResponseEntity<?> res = controller.createProduct(VALID_AUTH, makeRequest("   "));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("สร้างสำเร็จ → 201 พร้อม id")
        void created() {
            mockAuthenticated();
            when(productService.create(any())).thenReturn(42L);

            ResponseEntity<?> res = controller.createProduct(VALID_AUTH, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            SuccessResponse body = asSuccess(res);
            assertThat(body.getMessage()).isEqualTo("เพิ่มสินค้าสำเร็จ");
            assertThat(body.getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("insert ไม่ได้ id กลับมา → 500")
        void insertFailed() {
            mockAuthenticated();
            when(productService.create(any())).thenReturn(null);

            ResponseEntity<?> res = controller.createProduct(VALID_AUTH, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(asError(res).getMessage()).isEqualTo("ไม่สามารถเพิ่มสินค้าได้");
        }

        @Test
        @DisplayName("service พัง → 500")
        void serviceError() {
            mockAuthenticated();
            when(productService.create(any())).thenThrow(new RuntimeException("sql err"));

            ResponseEntity<?> res = controller.createProduct(VALID_AUTH, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(asError(res).getMessage()).contains("sql err");
        }
    }

    @Nested
    @DisplayName("PUT /api/products/{id}")
    class Update {
        @Test
        @DisplayName("ไม่มี token → 401 และไม่เรียก service")
        void unauthorized() {
            ResponseEntity<?> res = controller.updateProduct(BAD_AUTH, 1L, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(productService, never()).exists(any());
            verify(productService, never()).update(any(), any());
        }

        @Test
        @DisplayName("ไม่เจอสินค้า → 404 และไม่เรียก update")
        void notFound() {
            mockAuthenticated();
            when(productService.exists(99L)).thenReturn(false);

            ResponseEntity<?> res = controller.updateProduct(VALID_AUTH, 99L, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(productService, never()).update(any(), any());
        }

        @Test
        @DisplayName("แก้ไขสำเร็จ → 200 พร้อม id")
        void updated() {
            mockAuthenticated();
            when(productService.exists(1L)).thenReturn(true);
            when(productService.update(eq(1L), any())).thenReturn(true);

            ResponseEntity<?> res = controller.updateProduct(VALID_AUTH, 1L, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            SuccessResponse body = asSuccess(res);
            assertThat(body.getMessage()).isEqualTo("แก้ไขสินค้าสำเร็จ");
            assertThat(body.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("update ไม่มีแถวถูกแก้ → 500")
        void updateFailed() {
            mockAuthenticated();
            when(productService.exists(1L)).thenReturn(true);
            when(productService.update(eq(1L), any())).thenReturn(false);

            ResponseEntity<?> res = controller.updateProduct(VALID_AUTH, 1L, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(asError(res).getMessage()).isEqualTo("ไม่สามารถแก้ไขสินค้าได้");
        }

        @Test
        @DisplayName("service พัง → 500")
        void serviceError() {
            mockAuthenticated();
            when(productService.exists(1L)).thenReturn(true);
            when(productService.update(eq(1L), any())).thenThrow(new RuntimeException("err"));

            ResponseEntity<?> res = controller.updateProduct(VALID_AUTH, 1L, makeRequest("เค้ก"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("DELETE /api/products/{id}")
    class Delete {
        @Test
        @DisplayName("ไม่มี token → 401 และไม่เรียก service")
        void unauthorized() {
            ResponseEntity<?> res = controller.deleteProduct(BAD_AUTH, 1L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(productService, never()).exists(any());
            verify(productService, never()).delete(any());
        }

        @Test
        @DisplayName("ไม่เจอสินค้า → 404 และไม่เรียก delete")
        void notFound() {
            mockAuthenticated();
            when(productService.exists(99L)).thenReturn(false);

            ResponseEntity<?> res = controller.deleteProduct(VALID_AUTH, 99L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(productService, never()).delete(any());
        }

        @Test
        @DisplayName("ลบสำเร็จ → 200")
        void deleted() {
            mockAuthenticated();
            when(productService.exists(1L)).thenReturn(true);
            when(productService.delete(1L)).thenReturn(true);

            ResponseEntity<?> res = controller.deleteProduct(VALID_AUTH, 1L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(asSuccess(res).getMessage()).isEqualTo("ลบสินค้าสำเร็จ");
        }

        @Test
        @DisplayName("delete ไม่มีแถวถูกลบ → 500")
        void deleteFailed() {
            mockAuthenticated();
            when(productService.exists(1L)).thenReturn(true);
            when(productService.delete(1L)).thenReturn(false);

            ResponseEntity<?> res = controller.deleteProduct(VALID_AUTH, 1L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(asError(res).getMessage()).isEqualTo("ไม่สามารถลบสินค้าได้");
        }

        @Test
        @DisplayName("service พัง → 500")
        void serviceError() {
            mockAuthenticated();
            when(productService.exists(1L)).thenReturn(true);
            when(productService.delete(1L)).thenThrow(new RuntimeException("err"));

            ResponseEntity<?> res = controller.deleteProduct(VALID_AUTH, 1L);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}