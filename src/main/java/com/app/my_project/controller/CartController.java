package com.app.my_project.controller;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;

import javax.sql.DataSource;


@RestController
@RequestMapping("/api/cart")
@CrossOrigin(origins = "http://localhost:3000")
public class CartController {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired private DataSource dataSource;

    // ...

    // ==================== DTO Classes ====================

    public static class AddToCartRequest {
        private Long productId;
        private String name, category, image;
        private Integer price, quantity;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getPrice() { return price; }
        public void setPrice(Integer price) { this.price = price; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public static class UpdateCartRequest {
        private Integer quantity;
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }

    public static class CartItem {
        private Long id, productId;
        private Integer quantity, price, subtotal;
        private String email, productName, category, image;

        public CartItem(Long id, String email, Long productId, String productName, Integer quantity,
                Integer price, Integer subtotal, String category, String image) {
            this.id = id;
            this.email = email;
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.price = price;
            this.subtotal = subtotal;
            this.category = category;
            this.image = image;
        }

        public Long getId() { return id; }
        public String getEmail() { return email; }
        public Long getProductId() { return productId; }
        public String getProductName() { return productName; }
        public Integer getQuantity() { return quantity; }
        public Integer getPrice() { return price; }
        public String getCategory() { return category; }
        public String getImage() { return image; }
        public Integer getSubtotal() { return subtotal; }
    }

    public static class CartResponse {
        private List<CartItem> items;
        private Integer totalAmount, totalItems;

        public CartResponse(List<CartItem> items, Integer totalAmount, Integer totalItems) {
            this.items = items;
            this.totalAmount = totalAmount;
            this.totalItems = totalItems;
        }

        public List<CartItem> getItems() { return items; }
        public Integer getTotalAmount() { return totalAmount; }
        public Integer getTotalItems() { return totalItems; }
    }

    // ==================== Helper Methods ====================

    private Algorithm getAlgorithm() {
        return Algorithm.HMAC256(jwtSecret);  
    }

    // ✅ ใช้ connection จาก pool
    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // ✅ ใช้ connection ที่ส่งเข้ามา (ไม่สร้างใหม่)
    private String getUserEmailFromToken(Connection conn, String token) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT email FROM tb_userregister WHERE id = ?")) {
            JWTVerifier verifier = JWT.require(getAlgorithm()).build();
            Integer userId = Integer.parseInt(verifier.verify(token).getSubject());
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("email") : null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ✅ ใช้ try-with-resources ปิด PreparedStatement/ResultSet
    private Long getProductStock(Connection conn, Long productId) throws SQLException {
        String sql = "SELECT \"stockQuantity\" FROM tb_products WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong("stockQuantity") : 0L;
            }
        }
    }

    private void updateProductStock(Connection conn, Long productId, Long newStock) throws SQLException {
        String sql = "UPDATE tb_products SET \"stockQuantity\" = ?, \"isAvailable\" = ? WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, newStock);
            stmt.setBoolean(2, newStock > 0);
            stmt.setLong(3, productId);
            stmt.executeUpdate();
        }
    }

    // ==================== API Endpoints ====================

    @PostMapping("/add")
    public ResponseEntity<?> addToCart(@RequestHeader("Authorization") String authHeader,
            @RequestBody AddToCartRequest request) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(java.util.Collections.singletonMap("error", "กรุณาเข้าสู่ระบบ"));
            }

            if (request.getProductId() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(java.util.Collections.singletonMap("error", "กรุณาระบุรหัสสินค้า"));
            }

            int quantityToAdd = request.getQuantity() != null ? request.getQuantity() : 1;

            Long currentStock = getProductStock(conn, request.getProductId());

            if (currentStock <= 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(java.util.Collections.singletonMap("error", "สินค้าหมด"));
            }

            if (currentStock < quantityToAdd) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(java.util.Collections.singletonMap("error",
                                "สินค้าคงเหลือไม่เพียงพอ (เหลือ " + currentStock + " ชิ้น)"));
            }

            String productName = request.getName() != null ? request.getName() : "Unknown Product";
            Integer productPrice = request.getPrice() != null ? request.getPrice() : 0;
            String productCategory = request.getCategory() != null ? request.getCategory() : "other";
            String productImage = request.getImage() != null ? request.getImage() : "";

            // ✅ ตรวจสอบว่ามีสินค้าในตะกร้าแล้วหรือไม่
            try (PreparedStatement checkStmt = conn.prepareStatement(
                    "SELECT id, quantity, price FROM tb_cart WHERE email = ? AND product_id = ?")) {
                checkStmt.setString(1, email);
                checkStmt.setLong(2, request.getProductId());

                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        int currentQuantity = rs.getInt("quantity");
                        int priceInDb = rs.getInt("price");
                        int newQuantity = currentQuantity + quantityToAdd;
                        int newSubtotal = newQuantity * priceInDb;

                        try (PreparedStatement updateStmt = conn.prepareStatement(
                                "UPDATE tb_cart SET quantity = ?, subtotal = ? WHERE email = ? AND product_id = ?")) {
                            updateStmt.setInt(1, newQuantity);
                            updateStmt.setInt(2, newSubtotal);
                            updateStmt.setString(3, email);
                            updateStmt.setLong(4, request.getProductId());
                            updateStmt.executeUpdate();
                        }
                    } else {
                        int subtotal = quantityToAdd * productPrice;
                        try (PreparedStatement insertStmt = conn.prepareStatement(
                                "INSERT INTO tb_cart (email, product_id, product_name, quantity, price, category, image, subtotal) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                            insertStmt.setString(1, email);
                            insertStmt.setLong(2, request.getProductId());
                            insertStmt.setString(3, productName);
                            insertStmt.setInt(4, quantityToAdd);
                            insertStmt.setInt(5, productPrice);
                            insertStmt.setString(6, productCategory);
                            insertStmt.setString(7, productImage);
                            insertStmt.setInt(8, subtotal);
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }

            return ResponseEntity.ok(java.util.Collections.singletonMap("message", "เพิ่มสินค้าลงตะกร้าเรียบร้อย"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Collections.singletonMap("error", e.getMessage()));
        }
    }

    @GetMapping("")
    public ResponseEntity<?> getCart(@RequestHeader("Authorization") String authHeader) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, email, product_id, product_name, quantity, price, " +
                            "(quantity * price) as subtotal, category, image " +
                            "FROM tb_cart WHERE email = ? ORDER BY id DESC")) {
                stmt.setString(1, email);

                try (ResultSet rs = stmt.executeQuery()) {
                    List<CartItem> items = new ArrayList<>();
                    int totalAmount = 0, totalItems = 0;

                    while (rs.next()) {
                        int quantity = rs.getInt("quantity");
                        int subtotal = rs.getInt("subtotal");

                        items.add(new CartItem(
                                rs.getLong("id"), rs.getString("email"), rs.getLong("product_id"),
                                rs.getString("product_name"), quantity, rs.getInt("price"),
                                subtotal, rs.getString("category"), rs.getString("image")));

                        totalAmount += subtotal;
                        totalItems += quantity;
                    }

                    return ResponseEntity.ok(new CartResponse(items, totalAmount, totalItems));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{cartId}")
    public ResponseEntity<?> updateCart(@RequestHeader("Authorization") String authHeader,
            @PathVariable Long cartId, @RequestBody UpdateCartRequest request) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null || request.getQuantity() <= 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }

            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT product_id, quantity, price FROM tb_cart WHERE id = ? AND email = ?")) {
                selectStmt.setLong(1, cartId);
                selectStmt.setString(2, email);

                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                    }

                    Long productId = rs.getLong("product_id");
                    int currentQuantity = rs.getInt("quantity");
                    int newQuantity = request.getQuantity();
                    int quantityDiff = newQuantity - currentQuantity;

                }
            }

            try (PreparedStatement updateStmt = conn.prepareStatement(
                    "UPDATE tb_cart SET quantity = ?, subtotal = ? * price WHERE id = ? AND email = ?")) {
                updateStmt.setInt(1, request.getQuantity());
                updateStmt.setInt(2, request.getQuantity());
                updateStmt.setLong(3, cartId);
                updateStmt.setString(4, email);

                return updateStmt.executeUpdate() > 0
                        ? ResponseEntity.ok().build()
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{cartId}")
    public ResponseEntity<?> deleteFromCart(@RequestHeader("Authorization") String authHeader,
            @PathVariable Long cartId) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            Long productId;
            int quantity;

            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT product_id, quantity FROM tb_cart WHERE id = ? AND email = ?")) {
                selectStmt.setLong(1, cartId);
                selectStmt.setString(2, email);

                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                    }
                    productId = rs.getLong("product_id");
                    quantity = rs.getInt("quantity");
                }
            }

            try (PreparedStatement deleteStmt = conn.prepareStatement(
                    "DELETE FROM tb_cart WHERE id = ? AND email = ?")) {
                deleteStmt.setLong(1, cartId);
                deleteStmt.setString(2, email);

                return deleteStmt.executeUpdate() > 0
                        ? ResponseEntity.ok().build()
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/clear")
    public ResponseEntity<?> clearCart(@RequestHeader("Authorization") String authHeader) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            // คืน stock ทุกรายการ
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT product_id, quantity FROM tb_cart WHERE email = ?")) {
                selectStmt.setString(1, email);
            }

            try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM tb_cart WHERE email = ?")) {
                deleteStmt.setString(1, email);
                deleteStmt.executeUpdate();
            }

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}