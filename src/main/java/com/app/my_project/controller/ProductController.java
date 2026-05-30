package com.app.my_project.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.app.my_project.models.ProductModel;
import com.app.my_project.models.ProductRequest;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.ProductService;

import java.util.Optional;

/**
 * ProductController — บางลงหลัง refactor:
 *  - auth ใช้ JwtService (เลิก inline auth0 JWT)
 *  - data access ย้ายไป ProductService (เลิกเขียน raw JDBC ในคอนโทรลเลอร์)
 * เหลือหน้าที่: ตรวจ auth, validate input, map ผลลัพธ์ → HTTP status
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

    // ── Response DTOs ────────────────────────────────────────
    public static class ErrorResponse {
        private final String message;

        public ErrorResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class SuccessResponse {
        private final String message;
        private Long id;

        public SuccessResponse(String message) {
            this.message = message;
        }

        public SuccessResponse(String message, Long id) {
            this.message = message;
            this.id = id;
        }

        public String getMessage() {
            return message;
        }

        public Long getId() {
            return id;
        }
    }

    // ── helpers ──────────────────────────────────────────────
    private boolean isAuthenticated(String authHeader) {
        return jwtService.getUserIdFromHeader(authHeader) != null;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("กรุณาเข้าสู่ระบบ"));
    }

    private ResponseEntity<?> serverError(RuntimeException e) {
        log.error("ProductController error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
    }

    // ── endpoints ────────────────────────────────────────────
    @GetMapping("")
    public ResponseEntity<?> getAllProducts() {
        try {
            return ResponseEntity.ok(productService.getAll());
        } catch (RuntimeException e) {
            return serverError(e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProductById(@PathVariable Long id) {
        try {
            Optional<ProductModel> product = productService.getById(id);
            if (product.isPresent())
                return ResponseEntity.ok(product.get());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ไม่พบสินค้า"));
        } catch (RuntimeException e) {
            return serverError(e);
        }
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<?> getProductsByCategory(@PathVariable String category) {
        try {
            return ResponseEntity.ok(productService.getByCategory(category));
        } catch (RuntimeException e) {
            return serverError(e);
        }
    }

    @PostMapping("")
    public ResponseEntity<?> createProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ProductRequest request) {
        if (!isAuthenticated(authHeader))
            return unauthorized();
        if (request.getName() == null || request.getName().trim().isEmpty())
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("กรุณากรอกชื่อสินค้า"));

        try {
            Long newId = productService.create(request);
            if (newId != null)
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(new SuccessResponse("เพิ่มสินค้าสำเร็จ", newId));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("ไม่สามารถเพิ่มสินค้าได้"));
        } catch (RuntimeException e) {
            return serverError(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id, @RequestBody ProductRequest request) {
        if (!isAuthenticated(authHeader))
            return unauthorized();

        try {
            if (!productService.exists(id))
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ไม่พบสินค้า"));
            if (productService.update(id, request))
                return ResponseEntity.ok(new SuccessResponse("แก้ไขสินค้าสำเร็จ", id));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("ไม่สามารถแก้ไขสินค้าได้"));
        } catch (RuntimeException e) {
            return serverError(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        if (!isAuthenticated(authHeader))
            return unauthorized();

        try {
            if (!productService.exists(id))
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ไม่พบสินค้า"));
            if (productService.delete(id))
                return ResponseEntity.ok(new SuccessResponse("ลบสินค้าสำเร็จ"));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("ไม่สามารถลบสินค้าได้"));
        } catch (RuntimeException e) {
            return serverError(e);
        }
    }
}