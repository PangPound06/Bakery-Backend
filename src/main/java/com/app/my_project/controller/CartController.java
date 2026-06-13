package com.app.my_project.controller;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class CartController {

    public CartController(
            DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final Logger log = LoggerFactory.getLogger(CartController.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final DataSource dataSource;

    public static class AddToCartRequest {
        private Long productId;
        private String name, category, image;
        private Integer quantity;
        private Double price;
        private String selectedOption;

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

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

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public String getSelectedOption() {
            return selectedOption;
        }

        public void setSelectedOption(String selectedOption) {
            this.selectedOption = selectedOption;
        }
    }

    public static class UpdateCartRequest {
        private Integer quantity;

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    public static class CartItem {
        private Long id, productId;
        private Integer quantity, stock;
        private Double price, subtotal;
        private String email, productName, category, image;
        private String selectedOption;

        public CartItem(Long id, String email, Long productId, String productName, Integer quantity,
                Double price, Double subtotal, String category, String image, Integer stock, String selectedOption) {
            this.id = id;
            this.email = email;
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.price = price;
            this.subtotal = subtotal;
            this.category = category;
            this.image = image;
            this.stock = stock;
            this.selectedOption = selectedOption;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Long getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public Double getPrice() {
            return price;
        }

        public String getCategory() {
            return category;
        }

        public String getImage() {
            return image;
        }

        public Double getSubtotal() {
            return subtotal;
        }

        public Integer getStock() {
            return stock;
        }

        public String getSelectedOption() {
            return selectedOption;
        }
    }

    public static class CartResponse {
        private List<CartItem> items;
        private Double totalAmount;
        private Integer totalItems;

        public CartResponse(List<CartItem> items, Double totalAmount, Integer totalItems) {
            this.items = items;
            this.totalAmount = totalAmount;
            this.totalItems = totalItems;
        }

        public List<CartItem> getItems() {
            return items;
        }

        public Double getTotalAmount() {
            return totalAmount;
        }

        public Integer getTotalItems() {
            return totalItems;
        }
    }

    private int getDisplayQty(int quantity, String selectedOption) {
        if (selectedOption == null)
            return quantity;
        if (selectedOption.contains("2 ปอนด์"))
            return quantity / 16;
        if (selectedOption.contains("1 ปอนด์"))
            return quantity / 8;
        return quantity;
    }

    private Algorithm getAlgorithm() {
        return Algorithm.HMAC256(jwtSecret);
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private String getUserEmailFromToken(Connection conn, String token) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT email FROM tb_userregister WHERE id = ?")) {
            JWTVerifier verifier = JWT.require(getAlgorithm()).build();
            Integer userId = Integer.parseInt(verifier.verify(token).getSubject());
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("email") : null;
            }
        } catch (Exception e) {
            log.error("Error", e);
            return null;
        }
    }

    private Long getProductStock(Connection conn, Long productId) throws SQLException {
        String sql = "SELECT \"stockQuantity\" FROM tb_products WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong("stockQuantity") : 0L;
            }
        }
    }

    @PostMapping("/add")
    public ResponseEntity<?> addToCart(@RequestHeader("Authorization") String authHeader,
            @RequestBody AddToCartRequest request) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(java.util.Collections.singletonMap("error", "กรุณาเข้าสู่ระบบ"));

            if (request.getProductId() == null)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(java.util.Collections.singletonMap("error", "กรุณาระบุรหัสสินค้า"));

            int quantityToAdd = request.getQuantity() != null ? request.getQuantity() : 1;
            Long currentStock = getProductStock(conn, request.getProductId());

            if (currentStock != 9999L) {
                if (currentStock <= 0)
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(java.util.Collections.singletonMap("error", "สินค้าหมด"));
                if (currentStock < quantityToAdd)
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(java.util.Collections.singletonMap("error",
                                    "สินค้าคงเหลือไม่เพียงพอ (เหลือ " + currentStock + " ชิ้น)"));
            }

            if (currentStock == 9999L) {
                String countSql = "SELECT COALESCE(SUM(quantity), 0) FROM tb_cart WHERE email = ? AND product_id = ?";
                try (PreparedStatement countStmt = conn.prepareStatement(countSql)) {
                    countStmt.setString(1, email);
                    countStmt.setLong(2, request.getProductId());
                    try (ResultSet countRs = countStmt.executeQuery()) {
                        if (countRs.next()) {
                            int totalInCart = countRs.getInt(1);
                            if (totalInCart + quantityToAdd > 10) {
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body(java.util.Collections.singletonMap("error",
                                                "สินค้าทำสดสั่งได้รวมสูงสุด 10 แก้ว (ในตะกร้ามีแล้ว " + totalInCart
                                                        + " แก้ว)"));
                            }
                        }
                    }
                }
            }

            String productName = request.getName() != null ? request.getName() : "Unknown Product";
            double productPrice = request.getPrice() != null ? request.getPrice() : 0.0;
            String productCategory = request.getCategory() != null ? request.getCategory() : "other";
            String productImage = request.getImage() != null ? request.getImage() : "";
            String selectedOption = request.getSelectedOption();

            try (PreparedStatement checkStmt = conn.prepareStatement(
                    "SELECT id, quantity, price FROM tb_cart WHERE email = ? AND product_id = ? AND " +
                            "(selected_option = ? OR (selected_option IS NULL AND ? IS NULL))")) {
                checkStmt.setString(1, email);
                checkStmt.setLong(2, request.getProductId());
                checkStmt.setString(3, selectedOption);
                checkStmt.setString(4, selectedOption);

                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        int currentQuantity = rs.getInt("quantity");
                        int newQuantity = currentQuantity + quantityToAdd;

                        if (currentStock != 9999L && newQuantity > currentStock)
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body(java.util.Collections.singletonMap("error",
                                            "สินค้าคงเหลือไม่เพียงพอ (เหลือ " + currentStock + " ชิ้น, ในตะกร้ามีแล้ว "
                                                    + currentQuantity + " ชิ้น)"));

                        double priceInDb = rs.getDouble("price");
                        int displayQty = getDisplayQty(newQuantity, selectedOption);
                        double newSubtotal = displayQty * priceInDb;
                        long cartId = rs.getLong("id");

                        try (PreparedStatement updateStmt = conn.prepareStatement(
                                "UPDATE tb_cart SET quantity = ?, subtotal = ? WHERE id = ?")) {
                            updateStmt.setInt(1, newQuantity);
                            updateStmt.setDouble(2, newSubtotal);
                            updateStmt.setLong(3, cartId);
                            updateStmt.executeUpdate();
                        }
                    } else {
                        int displayQtyForInsert = getDisplayQty(quantityToAdd, selectedOption);
                        double subtotal = displayQtyForInsert * productPrice;

                        try (PreparedStatement insertStmt = conn.prepareStatement(
                                "INSERT INTO tb_cart (email, product_id, product_name, quantity, price, category, image, subtotal, selected_option) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                            insertStmt.setString(1, email);
                            insertStmt.setLong(2, request.getProductId());
                            insertStmt.setString(3, productName);
                            insertStmt.setInt(4, quantityToAdd);
                            insertStmt.setDouble(5, productPrice);
                            insertStmt.setString(6, productCategory);
                            insertStmt.setString(7, productImage);
                            insertStmt.setDouble(8, subtotal);
                            insertStmt.setString(9, selectedOption);
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }

            return ResponseEntity.ok(java.util.Collections.singletonMap("message", "เพิ่มสินค้าลงตะกร้าเรียบร้อย"));

        } catch (Exception e) {
            log.error("Error", e);
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

            try (PreparedStatement cleanStmt = conn.prepareStatement(
                    "DELETE FROM tb_cart WHERE product_id NOT IN (SELECT id FROM tb_products)")) {
                cleanStmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT c.id, c.email, c.product_id, c.product_name, c.quantity, c.price, " +
                            "c.subtotal, c.category, c.image, " +
                            "p.\"stockQuantity\" as stock, c.selected_option " +
                            "FROM tb_cart c " +
                            "LEFT JOIN tb_products p ON c.product_id = p.id " +
                            "WHERE c.email = ? ORDER BY c.id DESC")) {
                stmt.setString(1, email);

                try (ResultSet rs = stmt.executeQuery()) {
                    List<CartItem> items = new ArrayList<>();
                    double totalAmount = 0.0;
                    int totalItems = 0;

                    while (rs.next()) {
                        int quantity = rs.getInt("quantity");
                        double price = rs.getDouble("price");
                        String selectedOption = rs.getString("selected_option");
                        int displayQty = getDisplayQty(quantity, selectedOption);
                        double correctSubtotal = price * displayQty;

                        items.add(new CartItem(
                                rs.getLong("id"), rs.getString("email"), rs.getLong("product_id"),
                                rs.getString("product_name"),
                                displayQty,
                                price,
                                correctSubtotal,
                                rs.getString("category"), rs.getString("image"),
                                rs.getInt("stock"), selectedOption));

                        totalAmount += correctSubtotal;
                        totalItems += displayQty;
                    }

                    return ResponseEntity.ok(new CartResponse(items, totalAmount, totalItems));
                }
            }
        } catch (Exception e) {
            log.error("Error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{cartId}")
    public ResponseEntity<?> updateCart(@RequestHeader("Authorization") String authHeader,
            @PathVariable Long cartId, @RequestBody UpdateCartRequest request) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null || request.getQuantity() <= 0)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

            Long productId = null;
            String selectedOpt = null;
            double price = 0.0;

            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT id, product_id, selected_option, price FROM tb_cart WHERE id = ? AND email = ?")) {
                selectStmt.setLong(1, cartId);
                selectStmt.setString(2, email);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next())
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                    productId = rs.getLong("product_id");
                    selectedOpt = rs.getString("selected_option");
                    price = rs.getDouble("price");
                }
            }

            Long currentStock = getProductStock(conn, productId);
            if (currentStock != 9999L && request.getQuantity() > currentStock)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(java.util.Collections.singletonMap("error",
                                "สินค้าคงเหลือไม่เพียงพอ (เหลือ " + currentStock + " ชิ้น)"));

            if (currentStock == 9999L && request.getQuantity() > 10)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(java.util.Collections.singletonMap("error", "สินค้าทำสดสั่งได้สูงสุด 10 ชิ้น"));

            int displayQty = getDisplayQty(request.getQuantity(), selectedOpt);
            double newSubtotal = displayQty * price;

            try (PreparedStatement updateStmt = conn.prepareStatement(
                    "UPDATE tb_cart SET quantity = ?, subtotal = ? WHERE id = ? AND email = ?")) {
                updateStmt.setInt(1, request.getQuantity());
                updateStmt.setDouble(2, newSubtotal);
                updateStmt.setLong(3, cartId);
                updateStmt.setString(4, email);
                return updateStmt.executeUpdate() > 0
                        ? ResponseEntity.ok().build()
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        } catch (Exception e) {
            log.error("Error", e);
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

            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT id FROM tb_cart WHERE id = ? AND email = ?")) {
                selectStmt.setLong(1, cartId);
                selectStmt.setString(2, email);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next())
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
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
            log.error("Error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/clear")
    public ResponseEntity<?> clearCart(@RequestHeader("Authorization") String authHeader) {
        try (Connection conn = getConnection()) {
            String email = getUserEmailFromToken(conn, authHeader.replace("Bearer ", ""));
            if (email == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM tb_cart WHERE email = ?")) {
                deleteStmt.setString(1, email);
                deleteStmt.executeUpdate();
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}