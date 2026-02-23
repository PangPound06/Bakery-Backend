package com.app.my_project.controller;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.app.models.ProductModel;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;

import javax.sql.DataSource;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = {"http://localhost:3000", "https://bakery-frontend-next.vercel.app"})
public class ProductController {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private DataSource dataSource;

    // ==================== DTO Classes ====================

    public static class ProductRequest {
        private String name;
        private Long price;
        private String category;
        private String image;
        private String type;
        private String description;
        private Long stockQuantity;
        private Boolean isAvailable;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getPrice() {
            return price;
        }

        public void setPrice(Long price) {
            this.price = price;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Long getStockQuantity() {
            return stockQuantity;
        }

        public void setStockQuantity(Long stockQuantity) {
            this.stockQuantity = stockQuantity;
        }

        public Boolean getIsAvailable() {
            return isAvailable;
        }

        public void setIsAvailable(Boolean isAvailable) {
            this.isAvailable = isAvailable;
        }
    }

    public static class ErrorResponse {
        private String message;

        public ErrorResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class SuccessResponse {
        private String message;
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

    // ==================== Helper Methods ====================

    private Algorithm getAlgorithm() {
    return Algorithm.HMAC256(jwtSecret);
    }

    private boolean verifyToken(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer "))
                return false;
            String token = authHeader.replace("Bearer ", "");
            JWTVerifier verifier = JWT.require(getAlgorithm()).build();
            verifier.verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // ==================== API Endpoints ====================

    @GetMapping("")
    public ResponseEntity<?> getAllProducts() {
        try (Connection conn = getConnection()) {
            String sql = "SELECT id, name, price, category, image, type, description, " +
                    "\"stockQuantity\", \"isAvailable\" FROM tb_products ORDER BY id ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                List<ProductModel> products = new ArrayList<>();
                while (rs.next()) {
                    products.add(new ProductModel(
                            rs.getLong("id"), rs.getString("name"), rs.getLong("price"),
                            rs.getString("category"), rs.getString("image"), rs.getString("type"),
                            rs.getString("description"), rs.getLong("stockQuantity"), rs.getBoolean("isAvailable")));
                }
                return ResponseEntity.ok(products);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProductById(@PathVariable Long id) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT id, name, price, category, image, type, description, " +
                    "\"stockQuantity\", \"isAvailable\" FROM tb_products WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return ResponseEntity.ok(new ProductModel(
                                rs.getLong("id"), rs.getString("name"), rs.getLong("price"),
                                rs.getString("category"), rs.getString("image"), rs.getString("type"),
                                rs.getString("description"), rs.getLong("stockQuantity"),
                                rs.getBoolean("isAvailable")));
                    } else {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ไม่พบสินค้า"));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<?> getProductsByCategory(@PathVariable String category) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT id, name, price, category, image, type, description, " +
                    "\"stockQuantity\", \"isAvailable\" FROM tb_products " +
                    "WHERE LOWER(category) = LOWER(?) ORDER BY id ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, category);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<ProductModel> products = new ArrayList<>();
                    while (rs.next()) {
                        products.add(new ProductModel(
                                rs.getLong("id"), rs.getString("name"), rs.getLong("price"),
                                rs.getString("category"), rs.getString("image"), rs.getString("type"),
                                rs.getString("description"), rs.getLong("stockQuantity"),
                                rs.getBoolean("isAvailable")));
                    }
                    return ResponseEntity.ok(products);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @PostMapping("")
    public ResponseEntity<?> createProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ProductRequest request) {
        if (!verifyToken(authHeader))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("กรุณาเข้าสู่ระบบ"));
        if (request.getName() == null || request.getName().trim().isEmpty())
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("กรุณากรอกชื่อสินค้า"));

        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO tb_products (name, price, category, image, type, description, " +
                    "\"stockQuantity\", \"isAvailable\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, request.getName());
                stmt.setLong(2, request.getPrice() != null ? request.getPrice() : 0L);
                stmt.setString(3, request.getCategory() != null ? request.getCategory() : "other");
                stmt.setString(4, request.getImage() != null ? request.getImage() : "");
                stmt.setString(5, request.getType() != null ? request.getType() : "");
                stmt.setString(6, request.getDescription() != null ? request.getDescription() : "");
                stmt.setLong(7, request.getStockQuantity() != null ? request.getStockQuantity() : 0L);
                stmt.setBoolean(8, request.getIsAvailable() != null ? request.getIsAvailable() : true);

                int affectedRows = stmt.executeUpdate();
                if (affectedRows > 0) {
                    try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            return ResponseEntity.status(HttpStatus.CREATED)
                                    .body(new SuccessResponse("เพิ่มสินค้าสำเร็จ", generatedKeys.getLong(1)));
                        }
                    }
                }
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ErrorResponse("ไม่สามารถเพิ่มสินค้าได้"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id, @RequestBody ProductRequest request) {
        if (!verifyToken(authHeader))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("กรุณาเข้าสู่ระบบ"));

        try (Connection conn = getConnection()) {
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT id FROM tb_products WHERE id = ?")) {
                checkStmt.setLong(1, id);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next())
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ไม่พบสินค้า"));
                }
            }

            String sql = "UPDATE tb_products SET name = ?, price = ?, category = ?, " +
                    "image = ?, type = ?, description = ?, \"stockQuantity\" = ?, \"isAvailable\" = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, request.getName());
                stmt.setLong(2, request.getPrice() != null ? request.getPrice() : 0L);
                stmt.setString(3, request.getCategory() != null ? request.getCategory() : "other");
                stmt.setString(4, request.getImage() != null ? request.getImage() : "");
                stmt.setString(5, request.getType() != null ? request.getType() : "");
                stmt.setString(6, request.getDescription() != null ? request.getDescription() : "");
                stmt.setLong(7, request.getStockQuantity() != null ? request.getStockQuantity() : 0L);
                stmt.setBoolean(8, request.getIsAvailable() != null ? request.getIsAvailable() : true);
                stmt.setLong(9, id);

                int affectedRows = stmt.executeUpdate();
                if (affectedRows > 0)
                    return ResponseEntity.ok(new SuccessResponse("แก้ไขสินค้าสำเร็จ", id));
                else
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ErrorResponse("ไม่สามารถแก้ไขสินค้าได้"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        if (!verifyToken(authHeader))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("กรุณาเข้าสู่ระบบ"));

        try (Connection conn = getConnection()) {
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT id FROM tb_products WHERE id = ?")) {
                checkStmt.setLong(1, id);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next())
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ไม่พบสินค้า"));
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM tb_products WHERE id = ?")) {
                stmt.setLong(1, id);
                int affectedRows = stmt.executeUpdate();
                if (affectedRows > 0)
                    return ResponseEntity.ok(new SuccessResponse("ลบสินค้าสำเร็จ"));
                else
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ErrorResponse("ไม่สามารถลบสินค้าได้"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }
}