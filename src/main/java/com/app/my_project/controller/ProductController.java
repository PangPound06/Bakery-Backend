package com.app.my_project.controller;

import com.app.my_project.service.JwtService;
import com.app.my_project.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ProductController — Refactored
 *
 * เปลี่ยนแปลงจากเดิม (415 บรรทัด → ~120 บรรทัด):
 * - ลบ DataSource + raw JDBC ออกหมด → ใช้ ProductService แทน
 * - ลบ verifyToken() helper → ใช้ JwtService.decodeFromHeader() แทน
 * - ลบ ProductRequest static class → ใช้ Service.CreateProductRequest (record)
 * - Constructor injection
 * - แต่ละ endpoint สั้นและทำหน้าที่เดียว
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;
    private final JwtService jwtService;

    public ProductController(ProductService productService, JwtService jwtService) {
        this.productService = productService;
        this.jwtService = jwtService;
    }

    // ─── Public endpoints (ไม่ต้อง auth) ──────────────────────────

    @GetMapping("")
    public ResponseEntity<?> getAllProducts() {
        return ResponseEntity.ok(productService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProductById(@PathVariable Long id) {
        return productService.getById(id)
                .map(p -> ResponseEntity.ok((Object) p))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "ไม่พบสินค้า")));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<?> getProductsByCategory(@PathVariable String category) {
        return ResponseEntity.ok(productService.getByCategory(category));
    }

    // ─── Admin endpoints (ต้องมี token) ──────────────────────────

    @PostMapping("")
    public ResponseEntity<?> createProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ProductService.CreateProductRequest request) {
        if (!isAuthenticated(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "กรุณาเข้าสู่ระบบ"));
        }
        try {
            Long id = productService.create(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "เพิ่มสินค้าสำเร็จ", "id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create product", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody ProductService.CreateProductRequest request) {
        if (!isAuthenticated(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "กรุณาเข้าสู่ระบบ"));
        }
        try {
            boolean updated = productService.update(id, request);
            if (!updated) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "ไม่พบสินค้า"));
            }
            return ResponseEntity.ok(Map.of("message", "แก้ไขสินค้าสำเร็จ", "id", id));
        } catch (Exception e) {
            log.error("Failed to update product id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        if (!isAuthenticated(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "กรุณาเข้าสู่ระบบ"));
        }
        boolean deleted = productService.delete(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "ไม่พบสินค้า"));
        }
        return ResponseEntity.ok(Map.of("message", "ลบสินค้าสำเร็จ"));
    }

    // ─── Private helper ────────────────────────────────────────────

    private boolean isAuthenticated(String authHeader) {
        return jwtService.decodeFromHeader(authHeader) != null;
    }
}