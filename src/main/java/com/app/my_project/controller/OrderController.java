package com.app.my_project.controller;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.OrderItemRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class OrderController {

    private static final int FRESH_STOCK_VALUE = 9999;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DineInOrderRepository dineInOrderRepository;

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ✅ helper แปลง quantity ชิ้น → จำนวน order สำหรับ cake ปอนด์
    private int getDisplayQty(int quantity, String selectedOption) {
        if (selectedOption == null)
            return quantity;
        if (selectedOption.contains("2 ปอนด์"))
            return quantity / 16;
        if (selectedOption.contains("1 ปอนด์"))
            return quantity / 8;
        return quantity;
    }

    // ✅ helper สร้าง displayItem Map จาก OrderItemEntity
    private Map<String, Object> toDisplayItem(OrderItemEntity item) {
        Map<String, Object> displayItem = new HashMap<>();
        int displayQty = getDisplayQty(item.getQuantity(), item.getSelectedOption());
        displayItem.put("id", item.getId());
        displayItem.put("productId", item.getProductId());
        displayItem.put("productName", item.getProductName());
        displayItem.put("price", item.getPrice());
        displayItem.put("quantity", displayQty); // ✅ แปลงแล้ว เช่น 16 → 2 ออเดอร์
        displayItem.put("selectedOption", item.getSelectedOption());
        displayItem.put("image", item.getImage());
        return displayItem;
    }

    // ✅ Helper: decode JWT → userId
    private Long getUserIdFromToken(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer "))
                return null;
            String token = authHeader.replace("Bearer ", "");
            return Long.parseLong(
                    com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtSecret))
                            .build().verify(token).getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    // ✅ Helper: ตรวจว่าเป็น Admin
    private boolean isAdmin(Long userId) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT id FROM tb_admin WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {

            // ✅ ดึง email จาก token แทน request body
            String email = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication()
                    .getName();

            OrderEntity order = new OrderEntity();
            order.setEmail(email);
            order.setSubtotal(Double.parseDouble(request.get("subtotal").toString()));
            order.setShipping(Double.parseDouble(request.get("shipping").toString()));
            order.setTotal(Double.parseDouble(request.get("total").toString()));
            order.setPaymentMethod((String) request.get("paymentMethod"));
            order.setPaymentStatus((String) request.get("paymentStatus"));
            order.setPaymentId((String) request.get("paymentId"));
            order.setCreatedAt(LocalDateTime.now());

            String orderType = request.get("orderType") != null ? (String) request.get("orderType") : "online";
            order.setOrderType(orderType);

            String orderStatus = request.get("orderStatus") != null ? (String) request.get("orderStatus") : "pending";
            order.setOrderStatus(orderStatus);

            if (request.get("slipImage") != null)
                order.setSlipImage((String) request.get("slipImage"));

            order.setCardName((String) request.get("cardName"));
            order.setCardLast4((String) request.get("cardLast4"));

            @SuppressWarnings("unchecked")
            Map<String, String> shippingInfo = (Map<String, String>) request.get("shippingInfo");
            if (shippingInfo != null) {
                order.setReceiverName(shippingInfo.get("fullname"));
                order.setReceiverPhone(shippingInfo.get("phone"));
                order.setReceiverAddress(shippingInfo.get("address"));
                order.setNote(
                        shippingInfo.get("note") != null ? shippingInfo.get("note") : (String) request.get("note"));
            }

            if (request.get("note") != null && order.getNote() == null) {
                order.setNote((String) request.get("note"));
            }

            OrderEntity savedOrder = orderRepository.save(order);

            String ordCode = "ORD" + String.format("%06d", savedOrder.getId() * 104729L % 1000000L)
                    + savedOrder.getId();
            savedOrder.setOrdCode(ordCode);
            orderRepository.save(savedOrder);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
            if (items != null) {
                try (Connection conn = getConnection()) {
                    for (Map<String, Object> item : items) {
                        Long productId = Long.parseLong(item.get("productId").toString());
                        int quantity = Integer.parseInt(item.get("quantity").toString());

                        OrderItemEntity orderItem = new OrderItemEntity();
                        orderItem.setOrderId(savedOrder.getId());
                        orderItem.setProductId(productId);
                        orderItem.setProductName((String) item.get("productName"));
                        orderItem.setPrice(Double.parseDouble(item.get("price").toString()));
                        orderItem.setQuantity(quantity); // ✅ เก็บชิ้นจริง (16) สำหรับลด stock
                        String selectedOpt = item.get("selectedOption") != null ? item.get("selectedOption").toString()
                                : null;
                        orderItem.setSelectedOption(selectedOpt);
                        orderItem.setImage(item.get("image") != null ? item.get("image").toString() : null); // ✅ เพิ่ม
                        orderItemRepository.save(orderItem);

                        String updateStockSql = "UPDATE tb_products " +
                                "SET \"stockQuantity\" = GREATEST(\"stockQuantity\" - ?, 0), " +
                                "    \"isAvailable\"   = (GREATEST(\"stockQuantity\" - ?, 0) > 0) " +
                                "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                        try (PreparedStatement stockStmt = conn.prepareStatement(updateStockSql)) {
                            stockStmt.setInt(1, quantity);
                            stockStmt.setInt(2, quantity);
                            stockStmt.setLong(3, productId);
                            stockStmt.executeUpdate();
                        }
                    }
                }
            }

            response.put("success", true);
            response.put("message", "สร้างคำสั่งซื้อสำเร็จ");
            response.put("orderId", savedOrder.getId());
            response.put("ordCode", ordCode);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // GET /api/orders/user/{email} — เฉพาะ Admin หรือเจ้าของ
    @GetMapping("/user/{email}")
    public ResponseEntity<?> getOrdersByEmail(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String email) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        if (!isAdmin(userId)) {
            String tokenEmail = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
            if (!tokenEmail.equals(email))
                return ResponseEntity.status(403)
                        .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));
        }

        try {
            List<OrderEntity> orders = orderRepository.findByEmailOrderByCreatedAtDesc(email);
            List<Map<String, Object>> result = new ArrayList<>();
            for (OrderEntity order : orders) {
                Map<String, Object> o = new HashMap<>();
                o.put("id", order.getId());
                o.put("ordCode", order.getOrdCode());
                o.put("email", order.getEmail());
                o.put("subtotal", order.getSubtotal());
                o.put("shipping", order.getShipping());
                o.put("total", order.getTotal());
                o.put("paymentMethod", order.getPaymentMethod());
                o.put("paymentStatus", order.getPaymentStatus());
                o.put("orderStatus", order.getOrderStatus());
                o.put("orderType", order.getOrderType());
                o.put("receiverName", order.getReceiverName());
                o.put("receiverPhone", order.getReceiverPhone());
                o.put("receiverAddress", order.getReceiverAddress());
                o.put("note", order.getNote());
                o.put("slipImage", order.getSlipImage());
                o.put("createdAt", order.getCreatedAt()
                        .atZone(java.time.ZoneId.of("Asia/Bangkok"))
                        .toOffsetDateTime()
                        .toString());
                result.add(o);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    // GET /api/orders/{id} — เฉพาะ Admin หรือเจ้าของ order
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        Map<String, Object> response = new HashMap<>();
        Optional<OrderEntity> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบคำสั่งซื้อ");
            return ResponseEntity.badRequest().body(response);
        }

        OrderEntity order = orderOpt.get();

        // ✅ ตรวจว่าเป็น Admin หรือเจ้าของ order
        if (!isAdmin(userId)) {
            String tokenEmail = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
            if (!order.getEmail().equals(tokenEmail))
                return ResponseEntity.status(403)
                        .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));
        }

        List<OrderItemEntity> items = orderItemRepository.findByOrderId(id);
        List<Map<String, Object>> displayItems = new ArrayList<>();
        for (OrderItemEntity item : items) {
            Map<String, Object> di = toDisplayItem(item);
            if (di.get("image") == null) {
                try (Connection conn = getConnection()) {
                    String sql = "SELECT image FROM tb_products WHERE id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setLong(1, item.getProductId());
                        try (ResultSet rs = stmt.executeQuery()) {
                            di.put("image", rs.next() ? rs.getString("image") : null);
                        }
                    }
                } catch (Exception imgEx) {
                    di.put("image", null);
                }
            }
            displayItems.add(di);
        }

        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("id", order.getId());
        orderMap.put("ordCode", order.getOrdCode());
        orderMap.put("email", order.getEmail());
        orderMap.put("subtotal", order.getSubtotal());
        orderMap.put("shipping", order.getShipping());
        orderMap.put("total", order.getTotal());
        orderMap.put("paymentMethod", order.getPaymentMethod());
        orderMap.put("paymentStatus", order.getPaymentStatus());
        orderMap.put("orderStatus", order.getOrderStatus());
        orderMap.put("orderType", order.getOrderType());
        orderMap.put("receiverName", order.getReceiverName());
        orderMap.put("receiverPhone", order.getReceiverPhone());
        orderMap.put("receiverAddress", order.getReceiverAddress());
        orderMap.put("note", order.getNote());
        orderMap.put("slipImage", order.getSlipImage());
        orderMap.put("createdAt", order.getCreatedAt()
                .atZone(java.time.ZoneId.of("Asia/Bangkok"))
                .toOffsetDateTime()
                .toString());

        response.put("success", true);
        response.put("order", orderMap);
        response.put("items", displayItems);
        return ResponseEntity.ok(response);
    }

    // PUT /api/orders/{id}/status — เฉพาะ Admin
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!isAdmin(userId))
            return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));

        Map<String, Object> response = new HashMap<>();
        Optional<OrderEntity> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบคำสั่งซื้อ");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            OrderEntity order = orderOpt.get();
            if (request.containsKey("orderStatus"))
                order.setOrderStatus(request.get("orderStatus"));
            if (request.containsKey("paymentStatus"))
                order.setPaymentStatus(request.get("paymentStatus"));
            orderRepository.save(order);

            if ("dine-in".equals(order.getOrderType()) &&
                    "paid".equals(request.get("paymentStatus"))) {
                List<DineInOrderEntity> dineInOrders = dineInOrderRepository
                        .findByEmailOrderByCreatedAtDesc(order.getEmail());
                for (DineInOrderEntity dineIn : dineInOrders) {
                    if (!"cancelled".equals(dineIn.getOrderStatus())) {
                        dineIn.setPaymentStatus("paid");
                        dineInOrderRepository.save(dineIn);
                    }
                }
            }

            response.put("success", true);
            response.put("message", "อัพเดทสถานะสำเร็จ");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.badRequest().body(response);
        }
    }

    // GET /api/orders/all — เฉพาะ Admin
    @GetMapping("/all")
    public ResponseEntity<?> getAllOrders(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!isAdmin(userId))
            return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));

        return ResponseEntity.ok(
                orderRepository.findAllByOrderByCreatedAtDesc().stream().map(order -> {
                    Map<String, Object> o = new HashMap<>();
                    o.put("id", order.getId());
                    o.put("ordCode", order.getOrdCode());
                    o.put("email", order.getEmail());
                    o.put("total", order.getTotal());
                    o.put("orderStatus", order.getOrderStatus());
                    String ot = order.getOrderType();
                    if (ot == null) {
                        String note = order.getNote();
                        ot = (note != null && note.contains("โต๊ะ")) ? "dine-in" : "online";
                    }
                    o.put("orderType", ot);
                    o.put("paymentStatus", order.getPaymentStatus());
                    o.put("paymentMethod", order.getPaymentMethod());
                    o.put("createdAt", order.getCreatedAt()
                            .atZone(java.time.ZoneId.of("Asia/Bangkok"))
                            .toOffsetDateTime()
                            .toString());
                    o.put("receiverName", order.getReceiverName());
                    o.put("note", order.getNote());
                    o.put("slipImage", order.getSlipImage());
                    return o;
                }).toList());
    }

    // PUT /api/orders/{id}/cancel — Admin หรือเจ้าของ order
    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        Map<String, Object> response = new HashMap<>();
        Optional<OrderEntity> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบคำสั่งซื้อ");
            return ResponseEntity.badRequest().body(response);
        }

        OrderEntity order = orderOpt.get();

        // ✅ ถ้าไม่ใช่ Admin ต้องตรวจว่าเป็นเจ้าของ order
        if (!isAdmin(userId)) {
            String tokenEmail = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
            if (!order.getEmail().equals(tokenEmail))
                return ResponseEntity.status(403)
                        .body(Map.of("success", false, "message", "ไม่มีสิทธิ์ยกเลิก order นี้"));
        }

        try {
            if ("delivered".equals(order.getOrderStatus())) {
                response.put("success", false);
                response.put("message", "ไม่สามารถยกเลิกคำสั่งซื้อที่จัดส่งแล้ว");
                return ResponseEntity.badRequest().body(response);
            }
            List<OrderItemEntity> items = orderItemRepository.findByOrderId(id);
            try (Connection conn = getConnection()) {
                for (OrderItemEntity item : items) {
                    String restoreStockSql = "UPDATE tb_products " +
                            "SET \"stockQuantity\" = \"stockQuantity\" + ?, \"isAvailable\" = true " +
                            "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                    try (PreparedStatement stockStmt = conn.prepareStatement(restoreStockSql)) {
                        stockStmt.setInt(1, item.getQuantity());
                        stockStmt.setLong(2, item.getProductId());
                        stockStmt.executeUpdate();
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

    @GetMapping("/search/{orderId}")
    public ResponseEntity<?> searchByOrderId(@PathVariable String orderId) {
        Map<String, Object> response = new HashMap<>();
        try {
            String code = orderId.toUpperCase().trim();
            Optional<OrderEntity> orderOpt = orderRepository.findByOrdCode(code);
            if (orderOpt.isEmpty()) {
                List<OrderEntity> allOrders = orderRepository.findAll();
                for (OrderEntity o : allOrders) {
                    String generated = "ORD" + String.format("%06d", o.getId() * 104729L % 1000000L) + o.getId();
                    if (generated.equalsIgnoreCase(code)) {
                        orderOpt = Optional.of(o);
                        o.setOrdCode(generated);
                        orderRepository.save(o);
                        break;
                    }
                }
            }
            if (orderOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่พบคำสั่งซื้อ");
                return ResponseEntity.ok(response);
            }
            OrderEntity order = orderOpt.get();
            List<OrderItemEntity> items = orderItemRepository.findByOrderId(order.getId());

            // ✅ แปลง quantity → displayQty ก่อนส่ง Frontend
            List<Map<String, Object>> displayItems = new ArrayList<>();
            for (OrderItemEntity item : items) {
                displayItems.add(toDisplayItem(item));
            }

            response.put("success", true);
            response.put("order", order);
            response.put("items", displayItems); // ✅ ส่ง displayItems แทน items
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "หมายเลขคำสั่งซื้อไม่ถูกต้อง");
            return ResponseEntity.ok(response);
        }
    }

    @PutMapping("/backfill-ordcode")
    public ResponseEntity<?> backfillOrdCode() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<OrderEntity> orders = orderRepository.findAll();
            int count = 0;
            for (OrderEntity order : orders) {
                if (order.getOrdCode() == null || order.getOrdCode().isEmpty()) {
                    String code = "ORD" + String.format("%06d", order.getId() * 104729L % 1000000L) + order.getId();
                    order.setOrdCode(code);
                    orderRepository.save(order);
                    count++;
                }
            }
            response.put("success", true);
            response.put("message", "อัปเดต " + count + " รายการ");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/stats/top-products")
    public ResponseEntity<?> getTopProducts(
            @RequestParam(required = false, defaultValue = "all") String days) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT oi.product_name, oi.price, oi.quantity, oi.selected_option, " +
                    "o.created_at, o.subtotal, " +
                    "COALESCE(p.category, 'unknown') as category, " +
                    "(SELECT SUM(oi2.price * oi2.quantity) FROM tb_order_items oi2 WHERE oi2.order_id = o.id) as order_items_total "
                    +
                    "FROM tb_order_items oi " +
                    "JOIN tb_orders o ON oi.order_id = o.id " +
                    "LEFT JOIN tb_products p ON oi.product_id = p.id " +
                    "WHERE ( " +
                    "  (o.order_type = 'pos' AND o.order_status != 'cancelled') " +
                    "  OR " +
                    "  (o.order_status IN ('confirmed', 'preparing', 'shipping', 'delivered')) " +
                    ") ";

            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {

                Map<String, long[]> grouped = new LinkedHashMap<>();

                java.time.LocalDateTime cutoff = days.equals("all") ? java.time.LocalDateTime.MIN
                        : days.equals("7") ? java.time.LocalDateTime.now().minusDays(7)
                                : days.equals("30") ? java.time.LocalDateTime.now().minusDays(30)
                                        : java.time.LocalDateTime.MIN;

                while (rs.next()) {
                    java.time.LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                    if (createdAt.isBefore(cutoff))
                        continue;

                    String productName = rs.getString("product_name");
                    double price = rs.getDouble("price");
                    int quantity = rs.getInt("quantity");
                    String selectedOption = rs.getString("selected_option");

                    if (selectedOption != null) {
                        if (selectedOption.contains("1 ปอนด์"))
                            selectedOption = "1 ปอนด์ (8 ชิ้น)";
                        else if (selectedOption.contains("2 ปอนด์"))
                            selectedOption = "2 ปอนด์ (16 ชิ้น)";
                    }

                    int displayQty = getDisplayQty(quantity, selectedOption);
                    double orderSubtotal = rs.getDouble("subtotal");
                    double orderItemsTotal = rs.getDouble("order_items_total");

                    double itemRaw = price * quantity;
                    double ratio = orderItemsTotal > 0 ? itemRaw / orderItemsTotal : 0;
                    long revenue = (long) (orderSubtotal * ratio);

                    String category = rs.getString("category");
                    String key = productName + "|||" + (selectedOption != null ? selectedOption : "") + "|||"
                            + category;
                    grouped.putIfAbsent(key, new long[] { 0, 0, 0 });
                    long[] data = grouped.get(key);
                    data[0] += displayQty;
                    data[1] += revenue;
                    data[2] += 1;
                }

                // ✅ คำนวณยอดรวมจากทุกสินค้า (ไม่ใช่แค่ Top 10)
                long totalAllRevenue = grouped.values().stream().mapToLong(d -> d[1]).sum();
                long totalAllQty = grouped.values().stream().mapToLong(d -> d[0]).sum();
                long totalAllOrders = grouped.values().stream().mapToLong(d -> d[2]).sum();

                List<Map<String, Object>> topList = grouped.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                        .limit(10)
                        .map(entry -> {
                            String[] parts = entry.getKey().split("\\|\\|\\|", 3);
                            Map<String, Object> row = new HashMap<>();
                            row.put("productName", parts[0]);
                            row.put("selectedOption", parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null);
                            row.put("category", parts.length > 2 ? parts[2] : "unknown");
                            row.put("totalQty", entry.getValue()[0]);
                            row.put("totalRevenue", entry.getValue()[1]);
                            row.put("orderCount", entry.getValue()[2]);
                            return row;
                        })
                        .collect(java.util.stream.Collectors.toList());

                // ✅ ส่ง summary รวมทุกสินค้า + top 10 list
                Map<String, Object> response = new HashMap<>();
                response.put("topProducts", topList);
                response.put("totalAllRevenue", totalAllRevenue);
                response.put("totalAllQty", totalAllQty);
                response.put("totalAllOrders", totalAllOrders);
                response.put("totalProductCount", grouped.size());

                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(new ArrayList<>());
        }
    }

    @GetMapping("/my-dine-in")
    public ResponseEntity<?> getMyOrders(@RequestHeader("Authorization") String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication()
                    .getName();

            List<OrderEntity> orders = orderRepository.findByEmailOrderByCreatedAtDesc(email);

            List<Map<String, Object>> result = new ArrayList<>();
            for (OrderEntity order : orders) {
                if (!"dine-in".equals(order.getOrderType()))
                    continue;

                List<OrderItemEntity> items = orderItemRepository.findByOrderId(order.getId());
                List<Map<String, Object>> displayItems = new ArrayList<>();
                for (OrderItemEntity item : items) {
                    Map<String, Object> di = toDisplayItem(item);

                    // ✅ เพิ่ม image จาก tb_products
                    try (Connection conn = getConnection()) {
                        String sql = "SELECT image FROM tb_products WHERE id = ?";
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setLong(1, item.getProductId());
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    di.put("image", rs.getString("image"));
                                } else {
                                    di.put("image", null);
                                }
                            }
                        }
                    } catch (Exception imgEx) {
                        di.put("image", null);
                    }

                    displayItems.add(di);
                }

                Map<String, Object> o = new HashMap<>();
                o.put("id", order.getId());
                o.put("orderStatus", order.getOrderStatus());
                o.put("paymentStatus", order.getPaymentStatus());
                o.put("orderType", order.getOrderType());
                o.put("note", order.getNote());
                o.put("total", order.getTotal());
                o.put("subtotal", order.getSubtotal());
                o.put("createdAt", order.getCreatedAt()
                        .atZone(java.time.ZoneId.of("Asia/Bangkok"))
                        .toOffsetDateTime()
                        .toString());
                o.put("items", displayItems);
                result.add(o);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // POST /api/orders/{orderId}/items — Admin only
    @PostMapping("/{orderId}/items")
    public ResponseEntity<?> addOrderItem(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> request) {

        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!isAdmin(userId))
            return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));

        Map<String, Object> response = new HashMap<>();
        try {
            Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่พบคำสั่งซื้อ");
                return ResponseEntity.badRequest().body(response);
            }

            OrderEntity order = orderOpt.get();
            if ("delivered".equals(order.getOrderStatus()) || "cancelled".equals(order.getOrderStatus())) {
                response.put("success", false);
                response.put("message", "ไม่สามารถแก้ไขคำสั่งซื้อที่เสร็จสิ้นแล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            Long productId = Long.parseLong(request.get("productId").toString());
            String productName = (String) request.get("productName");
            double price = Double.parseDouble(request.get("price").toString());
            int displayQty = Integer.parseInt(request.get("quantity").toString());
            String selectedOption = request.get("selectedOption") != null
                    ? request.get("selectedOption").toString()
                    : null;
            String image = request.get("image") != null ? request.get("image").toString() : null;

            // แปลง displayQty → rawQty
            int multiplier = 1;
            if (selectedOption != null) {
                if (selectedOption.contains("2 ปอนด์"))
                    multiplier = 16;
                else if (selectedOption.contains("1 ปอนด์"))
                    multiplier = 8;
            }
            int rawQty = displayQty * multiplier;

            // ลด stock
            try (Connection conn = getConnection()) {
                String sql = "UPDATE tb_products " +
                        "SET \"stockQuantity\" = GREATEST(\"stockQuantity\" - ?, 0), " +
                        "    \"isAvailable\" = (GREATEST(\"stockQuantity\" - ?, 0) > 0) " +
                        "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, rawQty);
                    stmt.setInt(2, rawQty);
                    stmt.setLong(3, productId);
                    stmt.executeUpdate();
                }
            }

            // บันทึก item
            OrderItemEntity newItem = new OrderItemEntity();
            newItem.setOrderId(orderId);
            newItem.setProductId(productId);
            newItem.setProductName(productName);
            newItem.setPrice(price);
            newItem.setQuantity(rawQty);
            newItem.setSelectedOption(selectedOption);
            newItem.setImage(image);
            OrderItemEntity saved = orderItemRepository.save(newItem);

            // คำนวณยอดใหม่
            List<OrderItemEntity> allItems = orderItemRepository.findByOrderId(orderId);
            double newSubtotal = allItems.stream()
                    .mapToDouble(i -> i.getPrice() * getDisplayQty(i.getQuantity(), i.getSelectedOption()))
                    .sum();
            double newTotal = newSubtotal + order.getShipping();
            order.setSubtotal(newSubtotal);
            order.setTotal(newTotal);
            orderRepository.save(order);

            // ส่ง displayItem กลับ
            response.put("success", true);
            response.put("newItem", toDisplayItem(saved));
            response.put("newSubtotal", newSubtotal);
            response.put("newTotal", newTotal);
            response.put("message", "เพิ่มสินค้าสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // PATCH /api/orders/{orderId}/items/{itemId} — Admin only
    @PatchMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<?> updateOrderItemQuantity(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long orderId,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> request) {

        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!isAdmin(userId))
            return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));

        Map<String, Object> response = new HashMap<>();
        try {
            Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่พบคำสั่งซื้อ");
                return ResponseEntity.badRequest().body(response);
            }

            OrderEntity order = orderOpt.get();
            if ("delivered".equals(order.getOrderStatus()) || "cancelled".equals(order.getOrderStatus())) {
                response.put("success", false);
                response.put("message", "ไม่สามารถแก้ไขคำสั่งซื้อที่เสร็จสิ้นแล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<OrderItemEntity> itemOpt = orderItemRepository.findById(itemId);
            if (itemOpt.isEmpty() || !itemOpt.get().getOrderId().equals(orderId)) {
                response.put("success", false);
                response.put("message", "ไม่พบสินค้า");
                return ResponseEntity.badRequest().body(response);
            }

            OrderItemEntity item = itemOpt.get();
            int oldQty = item.getQuantity();
            int newDisplayQty = Integer.parseInt(request.get("quantity").toString());

            // แปลง displayQty → rawQty (คูณกลับ)
            int multiplier = 1;
            if (item.getSelectedOption() != null) {
                if (item.getSelectedOption().contains("2 ปอนด์"))
                    multiplier = 16;
                else if (item.getSelectedOption().contains("1 ปอนด์"))
                    multiplier = 8;
            }
            int newRawQty = newDisplayQty * multiplier;
            int diff = newRawQty - oldQty; // + = เพิ่ม, - = ลด

            // ปรับ stock
            try (Connection conn = getConnection()) {
                if (diff > 0) {
                    // ต้องการเพิ่ม → ลด stock
                    String sql = "UPDATE tb_products " +
                            "SET \"stockQuantity\" = GREATEST(\"stockQuantity\" - ?, 0), " +
                            "    \"isAvailable\" = (GREATEST(\"stockQuantity\" - ?, 0) > 0) " +
                            "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, diff);
                        stmt.setInt(2, diff);
                        stmt.setLong(3, item.getProductId());
                        stmt.executeUpdate();
                    }
                } else if (diff < 0) {
                    // ลดจำนวน → คืน stock
                    String sql = "UPDATE tb_products " +
                            "SET \"stockQuantity\" = \"stockQuantity\" + ?, \"isAvailable\" = true " +
                            "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, Math.abs(diff));
                        stmt.setLong(2, item.getProductId());
                        stmt.executeUpdate();
                    }
                }
            }

            // อัพเดท quantity
            item.setQuantity(newRawQty);
            orderItemRepository.save(item);

            // คำนวณยอดใหม่
            List<OrderItemEntity> allItems = orderItemRepository.findByOrderId(orderId);
            double newSubtotal = allItems.stream()
                    .mapToDouble(i -> i.getPrice() * getDisplayQty(i.getQuantity(), i.getSelectedOption()))
                    .sum();
            double newTotal = newSubtotal + order.getShipping();

            order.setSubtotal(newSubtotal);
            order.setTotal(newTotal);
            orderRepository.save(order);

            response.put("success", true);
            response.put("newSubtotal", newSubtotal);
            response.put("newTotal", newTotal);
            response.put("message", "แก้ไขจำนวนสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // DELETE /api/orders/{orderId}/items/{itemId} — Admin only
    @DeleteMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<?> removeOrderItem(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long orderId,
            @PathVariable Long itemId) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!isAdmin(userId))
            return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));

        Map<String, Object> response = new HashMap<>();
        try {
            Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่พบคำสั่งซื้อ");
                return ResponseEntity.badRequest().body(response);
            }

            OrderEntity order = orderOpt.get();

            if ("delivered".equals(order.getOrderStatus()) || "cancelled".equals(order.getOrderStatus())) {
                response.put("success", false);
                response.put("message", "ไม่สามารถแก้ไขคำสั่งซื้อที่เสร็จสิ้นแล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<OrderItemEntity> itemOpt = orderItemRepository.findById(itemId);
            if (itemOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่พบสินค้า");
                return ResponseEntity.badRequest().body(response);
            }

            OrderItemEntity item = itemOpt.get();
            if (!item.getOrderId().equals(orderId)) {
                response.put("success", false);
                response.put("message", "สินค้านี้ไม่ได้อยู่ใน order นี้");
                return ResponseEntity.badRequest().body(response);
            }

            // Restore stock
            try (Connection conn = getConnection()) {
                String restoreStockSql = "UPDATE tb_products " +
                        "SET \"stockQuantity\" = \"stockQuantity\" + ?, \"isAvailable\" = true " +
                        "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                try (PreparedStatement stmt = conn.prepareStatement(restoreStockSql)) {
                    stmt.setInt(1, item.getQuantity());
                    stmt.setLong(2, item.getProductId());
                    stmt.executeUpdate();
                }
            }

            // Remove item
            orderItemRepository.deleteById(itemId);

            // Recalculate order totals from remaining items
            List<OrderItemEntity> remaining = orderItemRepository.findByOrderId(orderId);

            if (remaining.isEmpty()) {
                // No items left — cancel the order
                order.setOrderStatus("cancelled");
                orderRepository.save(order);
                response.put("success", true);
                response.put("cancelled", true);
                response.put("message", "ไม่มีสินค้าเหลือ คำสั่งซื้อถูกยกเลิกอัตโนมัติ");
                return ResponseEntity.ok(response);
            }

            double newSubtotal = remaining.stream()
                    .mapToDouble(i -> i.getPrice() * getDisplayQty(i.getQuantity(), i.getSelectedOption()))
                    .sum();
            double newTotal = newSubtotal + order.getShipping();

            order.setSubtotal(newSubtotal);
            order.setTotal(newTotal);
            orderRepository.save(order);

            response.put("success", true);
            response.put("newSubtotal", newSubtotal);
            response.put("newTotal", newTotal);
            response.put("message", "ลบสินค้าสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}