package com.app.my_project.controller;

import com.app.my_project.models.ProductModel;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.ProductService;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test ProductController (refactored)
 *
 * Controller เป็น thin layer แล้ว — focus ที่ HTTP behavior:
 *  - status code ถูกหรือไม่
 *  - auth check
 *  - error mapping (IllegalArgumentException → 400)
 *  - empty case (Optional.empty → 404)
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock private ProductService productService;
    @Mock private JwtService jwtService;

    @InjectMocks
    private ProductController controller;

    private static final String AUTH_HEADER = "Bearer valid-token";
    private DecodedJWT validJwt;

    @BeforeEach
    void setUp() {
        // สร้าง DecodedJWT จริงเพื่อใช้เป็น return value (non-null)
        validJwt = JWT.require(Algorithm.HMAC256("test-secret"))
                .build()
                .verify(JWT.create().withSubject("1").sign(Algorithm.HMAC256("test-secret")));
    }

    private ProductService.CreateProductRequest sampleRequest() {
        return new ProductService.CreateProductRequest(
                "Cake", 100.0, "cakes", 1L,
                "img.jpg", "type", "desc", 10L, true, null
        );
    }

    private ProductModel sampleModel() {
        return new ProductModel(
                1L, "Cake", 100.0, "cakes", 1L, "เค้ก", "🎂",
                "img.jpg", "type", "desc", 10L, true, null
        );
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET endpoints (public)")
    class PublicGetTests {

        @Test
        @DisplayName("GET / → 200 + product list")
        void getAllProducts_returns200() {
            when(productService.getAll()).thenReturn(List.of(sampleModel()));

            ResponseEntity<?> response = controller.getAllProducts();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(productService).getAll();
        }

        @Test
        @DisplayName("GET /{id} มี → 200 + model")
        void getProductById_found_returns200() {
            when(productService.getById(1L)).thenReturn(Optional.of(sampleModel()));

            ResponseEntity<?> response = controller.getProductById(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(ProductModel.class);
        }

        @Test
        @DisplayName("GET /{id} ไม่มี → 404")
        void getProductById_notFound_returns404() {
            when(productService.getById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getProductById(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("GET /category/{slug} → 200")
        void getByCategory_returns200() {
            when(productService.getByCategory("cakes")).thenReturn(List.of(sampleModel()));

            ResponseEntity<?> response = controller.getProductsByCategory("cakes");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(productService).getByCategory("cakes");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST / — create (auth required)")
    class CreateTests {

        @Test
        @DisplayName("ไม่มี token → 401 + ไม่เรียก service")
        void create_noAuth_returns401() {
            when(jwtService.decodeFromHeader(null)).thenReturn(null);

            ResponseEntity<?> response = controller.createProduct(null, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(productService, never()).create(any());
        }

        @Test
        @DisplayName("Token ปลอม → 401")
        void create_invalidToken_returns401() {
            when(jwtService.decodeFromHeader("Bearer fake")).thenReturn(null);

            ResponseEntity<?> response = controller.createProduct("Bearer fake", sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Happy path: 201 + id")
        void create_validRequest_returns201() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.create(any())).thenReturn(42L);

            ResponseEntity<?> response = controller.createProduct(AUTH_HEADER, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("id", 42L);
        }

        @Test
        @DisplayName("✅ Service throws IllegalArgumentException → 400 (validation error)")
        void create_validationError_returns400() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.create(any()))
                    .thenThrow(new IllegalArgumentException("กรุณากรอกชื่อสินค้า"));

            ResponseEntity<?> response = controller.createProduct(AUTH_HEADER, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat((String) body.get("message")).contains("ชื่อสินค้า");
        }

        @Test
        @DisplayName("Service throws unexpected exception → 500")
        void create_unexpectedError_returns500() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.create(any()))
                    .thenThrow(new RuntimeException("DB error"));

            ResponseEntity<?> response = controller.createProduct(AUTH_HEADER, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /{id} — update")
    class UpdateTests {

        @Test
        @DisplayName("ไม่มี token → 401")
        void update_noAuth_returns401() {
            when(jwtService.decodeFromHeader(null)).thenReturn(null);

            ResponseEntity<?> response = controller.updateProduct(null, 1L, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(productService, never()).update(any(), any());
        }

        @Test
        @DisplayName("Update มี → 200")
        void update_existing_returns200() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.update(eq(1L), any())).thenReturn(true);

            ResponseEntity<?> response = controller.updateProduct(AUTH_HEADER, 1L, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Update ไม่มี → 404")
        void update_notFound_returns404() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.update(eq(999L), any())).thenReturn(false);

            ResponseEntity<?> response = controller.updateProduct(AUTH_HEADER, 999L, sampleRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /{id}")
    class DeleteTests {

        @Test
        @DisplayName("ไม่มี token → 401")
        void delete_noAuth_returns401() {
            when(jwtService.decodeFromHeader(null)).thenReturn(null);

            ResponseEntity<?> response = controller.deleteProduct(null, 1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(productService, never()).delete(any());
        }

        @Test
        @DisplayName("ลบสำเร็จ → 200")
        void delete_success_returns200() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.delete(1L)).thenReturn(true);

            ResponseEntity<?> response = controller.deleteProduct(AUTH_HEADER, 1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ลบไม่มี → 404")
        void delete_notFound_returns404() {
            when(jwtService.decodeFromHeader(AUTH_HEADER)).thenReturn(validJwt);
            when(productService.delete(999L)).thenReturn(false);

            ResponseEntity<?> response = controller.deleteProduct(AUTH_HEADER, 999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}