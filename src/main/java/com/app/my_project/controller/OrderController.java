package com.app.my_project.controller;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.OrderItemRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "https://poundbakery.vercel.app")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    // ✅ เปลี่ยนจาก ProductRepository → DataSource เพื่อใช้ raw SQL
    @Autowired
    private DataSource dataSource;

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // สร้าง Order ใหม่ + ตัด Stock
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 1. สร้าง Order
            OrderEntity order = new OrderEntity();
            order.setEmail((String) request.get("email"));
            order.setSubtotal(Double.parseDouble(request.get("subtotal").toString()));
            order.setShipping(Double.parseDouble(request.get("shipping").toString()));
            order.setTotal(Double.parseDouble(request.get("total").toString()));
            order.setPaymentMethod((String) request.get("paymentMethod"));
            order.setPaymentStatus((String) request.get("paymentStatus"));
            order.setPaymentId((String) request.get("paymentId"));
            order.setOrderStatus("pending");
            order.setCreatedAt(LocalDateTime.now());

            if (request.get("slipImage") != null) {
                order.setSlipImage((String) request.get("slipImage"));
            }

            order.setCardName((String) request.get("cardName"));
            order.setCardLast4((String) request.get("cardLast4"));

            @SuppressWarnings("unchecked")
            Map<String, String> shippingInfo = (Map<String, String>) request.get("shippingInfo");
            if (shippingInfo != null) {
                order.setReceiverName(shippingInfo.get("fullname"));
                order.setReceiverPhone(shippingInfo.get("phone"));
                order.setReceiverAddress(shippingInfo.get("address"));
                order.setNote(shippingInfo.get("note"));
            }

            // บันทึก Order
            OrderEntity savedOrder = orderRepository.save(order);
            System.out.println("✅ Order created: ID=" + savedOrder.getId());

            // 2. บันทึก Items + ตัด Stock ด้วย raw SQL
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");

            if (items != null) {
                try (Connection conn = getConnection()) {
                    for (Map<String, Object> item : items) {
                        Long productId = Long.parseLong(item.get("productId").toString());
                        int quantity = Integer.parseInt(item.get("quantity").toString());

                        System.out.println("🔍 Processing productId: " + productId + ", quantity: " + quantity);

                        // บันทึก OrderItem
                        OrderItemEntity orderItem = new OrderItemEntity();
                        orderItem.setOrderId(savedOrder.getId());
                        orderItem.setProductId(productId);
                        orderItem.setProductName((String) item.get("productName"));
                        orderItem.setPrice(Double.parseDouble(item.get("price").toString()));
                        orderItem.setQuantity(quantity);
                        orderItemRepository.save(orderItem);

                        // ✅ ตัด Stock ด้วย raw SQL — แก้ปัญหา JPA map column ผิด
                        String updateStockSql = "UPDATE tb_products " +
                                "SET \"stockQuantity\" = GREATEST(\"stockQuantity\" - ?, 0), " +
                                "    \"isAvailable\"   = (GREATEST(\"stockQuantity\" - ?, 0) > 0) " +
                                "WHERE id = ?";

                        try (PreparedStatement stockStmt = conn.prepareStatement(updateStockSql)) {
                            stockStmt.setInt(1, quantity);
                            stockStmt.setInt(2, quantity);
                            stockStmt.setLong(3, productId);
                            int rows = stockStmt.executeUpdate();
                            System.out.println(
                                    "✅ Stock updated for productId: " + productId + " (" + rows + " row affected)");
                        }
                    }
                }
            }

            response.put("success", true);
            response.put("message", "สร้างคำสั่งซื้อสำเร็จ");
            response.put("orderId", savedOrder.getId());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ดึง Orders ของ User (ใช้ email)
    @GetMapping("/user/{email}")
    public ResponseEntity<?> getOrdersByEmail(@PathVariable String email) {
        try {
            List<OrderEntity> orders = orderRepository.findByEmailOrderByCreatedAtDesc(email);
            return ResponseEntity.ok(orders);
        } catch (Exception e) {
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    // ดึง Order ตาม ID พร้อม Items
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        Optional<OrderEntity> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบคำสั่งซื้อ");
            return ResponseEntity.badRequest().body(response);
        }

        OrderEntity order = orderOpt.get();
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(id);

        response.put("success", true);
        response.put("order", order);
        response.put("items", items);

        return ResponseEntity.ok(response);
    }

    // อัพเดทสถานะ Order
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        Optional<OrderEntity> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบคำสั่งซื้อ");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            OrderEntity order = orderOpt.get();

            if (request.containsKey("orderStatus")) {
                order.setOrderStatus(request.get("orderStatus"));
            }
            if (request.containsKey("paymentStatus")) {
                order.setPaymentStatus(request.get("paymentStatus"));
            }

            orderRepository.save(order);

            response.put("success", true);
            response.put("message", "อัพเดทสถานะสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ดึง Orders ทั้งหมด (สำหรับ Admin)
    // ดึง Orders ทั้งหมด (สำหรับ Admin)
    @GetMapping("/all")
    public ResponseEntity<?> getAllOrders() {
        return ResponseEntity.ok(
                orderRepository.findAllByOrderByCreatedAtDesc().stream().map(order -> {
                    Map<String, Object> o = new HashMap<>();
                    o.put("id", order.getId());
                    o.put("email", order.getEmail());
                    o.put("total", order.getTotal());
                    o.put("orderStatus", order.getOrderStatus());
                    o.put("paymentStatus", order.getPaymentStatus());
                    o.put("createdAt", order.getCreatedAt());
                    // ไม่ส่ง: slipImage, cardName, cardLast4, paymentId, receiverName,
                    // receiverPhone, receiverAddress
                    return o;
                }).toList());
    }

    // ยกเลิก Order + คืน Stock ด้วย raw SQL
    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        Optional<OrderEntity> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบคำสั่งซื้อ");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            OrderEntity order = orderOpt.get();

            if ("delivered".equals(order.getOrderStatus())) {
                response.put("success", false);
                response.put("message", "ไม่สามารถยกเลิกคำสั่งซื้อที่จัดส่งแล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            // ✅ คืน Stock ด้วย raw SQL
            List<OrderItemEntity> items = orderItemRepository.findByOrderId(id);
            try (Connection conn = getConnection()) {
                for (OrderItemEntity item : items) {
                    String restoreStockSql = "UPDATE tb_products " +
                            "SET \"stockQuantity\" = \"stockQuantity\" + ?, " +
                            "    \"isAvailable\"   = true " +
                            "WHERE id = ?";

                    try (PreparedStatement stockStmt = conn.prepareStatement(restoreStockSql)) {
                        stockStmt.setInt(1, item.getQuantity());
                        stockStmt.setLong(2, item.getProductId());
                        stockStmt.executeUpdate();
                        System.out.println("✅ Stock restored for productId: " + item.getProductId());
                    }
                }
            }

            order.setOrderStatus("cancelled");
            orderRepository.save(order);

            response.put("success", true);
            response.put("message", "ยกเลิกคำสั่งซื้อสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ค้นหา Order ด้วย ORD number
    @GetMapping("/search/{orderId}")
    public ResponseEntity<?> searchByOrderId(@PathVariable String orderId) {
        Map<String, Object> response = new HashMap<>();

        try {
            String numericId = orderId.replace("ORD", "").replaceAll("[^0-9]", "");
            Long id = Long.parseLong(numericId);

            Optional<OrderEntity> orderOpt = orderRepository.findById(id);
            if (orderOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่พบคำสั่งซื้อ");
                return ResponseEntity.ok(response);
            }

            OrderEntity order = orderOpt.get();
            List<OrderItemEntity> items = orderItemRepository.findByOrderId(id);

            response.put("success", true);
            response.put("order", order);
            response.put("items", items);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "หมายเลขคำสั่งซื้อไม่ถูกต้อง");
            return ResponseEntity.ok(response);
        }
    }
}