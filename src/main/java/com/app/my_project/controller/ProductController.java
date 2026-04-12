package com.app.my_project.controller;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.app.my_project.models.ProductModel;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;

import javax.sql.DataSource;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class ProductController {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private DataSource dataSource;

    public static class ProductRequest {
        private String name;
        private Double price;
        private String category;
        private Long categoryId;
        private String image;
        private String type;
        private String description;
        private Long stockQuantity;
        private Boolean isAvailable;
        private String options;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Double getPrice() {
            return price;
        }

        public void setPrice(Double price) {
            this.price = price;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
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

        public String getOptions() {
            return options;
        }

        public void setOptions(String options) {
            this.options = options;
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

    // ✅ Helper: สร้าง ProductModel จาก ResultSet (JOIN กับ tb_categories)
    private ProductModel mapProduct(ResultSet rs) throws SQLException {
        return new ProductModel(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getDouble("price"),
                rs.getString("cat_slug"), // category (slug)
                rs.getLong("category_id"), // categoryId
                rs.getString("cat_name"), // categoryName
                rs.getString("cat_icon"), // categoryIcon
                rs.getString("image"),
                rs.getString("type"),
                rs.getString("description"),
                rs.getLong("stockQuantity"),
                rs.getBoolean("isAvailable"),
                rs.getString("options"));
    }

    // ✅ SQL SELECT พื้นฐานที่ JOIN กับ tb_categories
    private static final String BASE_SELECT = "SELECT p.id, p.name, p.price, p.category_id, p.image, p.type, p.description, "
            +
            "p.\"stockQuantity\", p.\"isAvailable\", p.options, " +
            "c.slug AS cat_slug, c.name AS cat_name, c.icon AS cat_icon " +
            "FROM tb_products p " +
            "LEFT JOIN tb_categories c ON p.category_id = c.id ";

    @GetMapping("")
    public ResponseEntity<?> getAllProducts() {
        try (Connection conn = getConnection()) {
            String sql = BASE_SELECT + "ORDER BY p.id ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                List<ProductModel> products = new ArrayList<>();
                while (rs.next())
                    products.add(mapProduct(rs));
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
            String sql = BASE_SELECT + "WHERE p.id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next())
                        return ResponseEntity.ok(mapProduct(rs));
                    else
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(new ErrorResponse("ไม่พบสินค้า"));
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
            String sql = BASE_SELECT + "WHERE LOWER(c.slug) = LOWER(?) ORDER BY p.id ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, category);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<ProductModel> products = new ArrayList<>();
                    while (rs.next())
                        products.add(mapProduct(rs));
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
            // ✅ หา category_id จาก slug หรือ categoryId
            Long categoryId = request.getCategoryId();
            if (categoryId == null && request.getCategory() != null) {
                String findCatSql = "SELECT id FROM tb_categories WHERE LOWER(slug) = LOWER(?)";
                try (PreparedStatement catStmt = conn.prepareStatement(findCatSql)) {
                    catStmt.setString(1, request.getCategory());
                    try (ResultSet catRs = catStmt.executeQuery()) {
                        if (catRs.next())
                            categoryId = catRs.getLong("id");
                    }
                }
            }

            String sql = "INSERT INTO tb_products (name, price, category, category_id, image, type, description, " +
                    "\"stockQuantity\", \"isAvailable\", options) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, request.getName());
                stmt.setDouble(2, request.getPrice() != null ? request.getPrice() : 0.0);
                stmt.setString(3, request.getCategory() != null ? request.getCategory() : "other");
                if (categoryId != null)
                    stmt.setLong(4, categoryId);
                else
                    stmt.setNull(4, java.sql.Types.BIGINT);
                stmt.setString(5, request.getImage() != null ? request.getImage() : "");
                stmt.setString(6, request.getType() != null ? request.getType() : "");
                stmt.setString(7, request.getDescription() != null ? request.getDescription() : "");
                stmt.setLong(8, request.getStockQuantity() != null ? request.getStockQuantity() : 0L);
                stmt.setBoolean(9, request.getIsAvailable() != null ? request.getIsAvailable() : true);
                stmt.setString(10, request.getOptions());

                int affectedRows = stmt.executeUpdate();
                if (affectedRows > 0) {
                    try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                        if (generatedKeys.next())
                            return ResponseEntity.status(HttpStatus.CREATED)
                                    .body(new SuccessResponse("เพิ่มสินค้าสำเร็จ", generatedKeys.getLong(1)));
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

            // ✅ หา category_id
            Long categoryId = request.getCategoryId();
            if (categoryId == null && request.getCategory() != null) {
                String findCatSql = "SELECT id FROM tb_categories WHERE LOWER(slug) = LOWER(?)";
                try (PreparedStatement catStmt = conn.prepareStatement(findCatSql)) {
                    catStmt.setString(1, request.getCategory());
                    try (ResultSet catRs = catStmt.executeQuery()) {
                        if (catRs.next())
                            categoryId = catRs.getLong("id");
                    }
                }
            }

            String sql = "UPDATE tb_products SET name = ?, price = ?, category = ?, category_id = ?, " +
                    "image = ?, type = ?, description = ?, \"stockQuantity\" = ?, \"isAvailable\" = ?, options = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, request.getName());
                stmt.setDouble(2, request.getPrice() != null ? request.getPrice() : 0.0);
                stmt.setString(3, request.getCategory() != null ? request.getCategory() : "other");
                if (categoryId != null)
                    stmt.setLong(4, categoryId);
                else
                    stmt.setNull(4, java.sql.Types.BIGINT);
                stmt.setString(5, request.getImage() != null ? request.getImage() : "");
                stmt.setString(6, request.getType() != null ? request.getType() : "");
                stmt.setString(7, request.getDescription() != null ? request.getDescription() : "");
                stmt.setLong(8, request.getStockQuantity() != null ? request.getStockQuantity() : 0L);
                stmt.setBoolean(9, request.getIsAvailable() != null ? request.getIsAvailable() : true);
                stmt.setString(10, request.getOptions());
                stmt.setLong(11, id);

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