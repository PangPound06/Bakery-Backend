package com.app.my_project.controller;

import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

@RestController
@RequestMapping("/api/dinein")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class DineInOrderController {

    @Autowired
    private DineInOrderRepository dineInOrderRepository;

    @Autowired
    private DineInOrderItemRepository dineInOrderItemRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    private static final int FRESH_STOCK_VALUE = 9999;

    // ✅ FIX #3: สถานะที่อนุญาตให้ admin เปลี่ยนได้
    private static final Set<String> VALID_STATUSES = Set.of(
            "pending", "preparing", "ready", "completed");

    // ✅ FIX #5: SSE — เก็บ connection ของทุก client
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // ✅ SSE endpoint — client เชื่อมต่อเพื่อรับ event แบบ real-time
    @GetMapping("/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    // ✅ ส่ง event แจ้งทุก client ที่เชื่อมต่ออยู่
    private void notifyClients() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("order-update").data("updated"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    // ✅ Helper: filter ออเดอร์ + โต๊ะตรง + ไม่ถูกยกเลิก + ยังไม่จ่าย
    private List<DineInOrderEntity> getActiveOrders(String email, String tableNo) {
        List<DineInOrderEntity> orders = dineInOrderRepository.findByEmailOrderByCreatedAtDesc(email);
        List<DineInOrderEntity> filtered = new ArrayList<>();
        for (DineInOrderEntity order : orders) {
            if (tableNo == null || tableNo.isEmpty() || tableNo.equals(order.getTableNo())) {
                if (!"cancelled".equals(order.getOrderStatus())
                        && !"paid".equals(order.getPaymentStatus())) {
                    filtered.add(order);
                }
            }
        }
        return filtered;
    }

    // ✅ User สั่งอาหาร
    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();

            DineInOrderEntity order = new DineInOrderEntity();
            order.setEmail(email);
            order.setTableNo((String) request.get("tableNo"));
            order.setSubtotal(Double.parseDouble(request.get("subtotal").toString()));
            order.setTotal(Double.parseDouble(request.get("total").toString()));
            order.setOrderStatus("pending");
            order.setCreatedAt(LocalDateTime.now());

            DineInOrderEntity saved = dineInOrderRepository.save(order);

            String ordCode = "ORD" + String.format("%06d", saved.getId() * 104729L % 1000000L) + saved.getId();
            saved.setOrdCode(ordCode);
            dineInOrderRepository.save(saved);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
            if (items != null) {
                try (Connection conn = dataSource.getConnection()) {
                    for (Map<String, Object> item : items) {
                        int quantity = Integer.parseInt(item.get("quantity").toString());
                        Long productId = Long.parseLong(item.get("productId").toString());

                        DineInOrderItemEntity orderItem = new DineInOrderItemEntity();
                        orderItem.setOrderId(saved.getId());
                        orderItem.setProductId(productId);
                        orderItem.setProductName((String) item.get("productName"));
                        orderItem.setPrice(Double.parseDouble(item.get("price").toString()));
                        orderItem.setQuantity(quantity);
                        orderItem.setSelectedOption(
                                item.get("selectedOption") != null ? item.get("selectedOption").toString() : null);
                        orderItem.setImage(item.get("image") != null ? item.get("image").toString() : null);
                        dineInOrderItemRepository.save(orderItem);

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

            notifyClients();

            response.put("success", true);
            response.put("orderId", saved.getId());
            response.put("ordCode", ordCode);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ✅ ลูกค้าส่งสลิปชำระเงิน
    @PostMapping("/request-bill")
    public ResponseEntity<?> requestBill(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();

            String slipImage = (String) request.get("slipImage");
            String tableNo = (String) request.get("tableNo");

            // ✅ FIX #2: ใช้ getActiveOrders filter วันนี้ + โต๊ะ แทนดึงทั้งหมด
            List<DineInOrderEntity> activeOrders = getActiveOrders(email, tableNo);

            // ✅ FIX #6: รวมเฉพาะออเดอร์ที่ completed เท่านั้น
            List<DineInOrderEntity> completedOrders = new ArrayList<>();
            for (DineInOrderEntity order : activeOrders) {
                if ("completed".equals(order.getOrderStatus())) {
                    order.setSlipImage(slipImage);
                    order.setPaymentStatus("pending_verify");
                    dineInOrderRepository.save(order);
                    completedOrders.add(order);
                }
            }

            if (completedOrders.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่มีออเดอร์ที่เสร็จแล้วสำหรับเรียกเก็บเงิน");
                return ResponseEntity.badRequest().body(response);
            }

            Double buffetPricePerPerson = request.get("buffetPrice") != null
                    ? Double.parseDouble(request.get("buffetPrice").toString())
                    : null;
            int buffetPax = request.get("buffetPax") != null
                    ? Integer.parseInt(request.get("buffetPax").toString())
                    : 1;
            Double buffetPrice = (buffetPricePerPerson != null)
                    ? buffetPricePerPerson * buffetPax
                    : null;

            double grandTotal = (buffetPrice != null && buffetPrice > 0)
                    ? buffetPrice
                    : completedOrders.stream().mapToDouble(DineInOrderEntity::getTotal).sum();
            double grandSubtotal = (buffetPrice != null && buffetPrice > 0)
                    ? buffetPrice
                    : completedOrders.stream().mapToDouble(DineInOrderEntity::getSubtotal).sum();

            String billTableNo = completedOrders.get(0).getTableNo();

            OrderEntity adminOrder = new OrderEntity();
            adminOrder.setEmail(email);
            adminOrder.setSubtotal(grandSubtotal);
            adminOrder.setShipping(0.0);
            adminOrder.setTotal(grandTotal);
            adminOrder.setPaymentMethod("qr_promptpay");
            adminOrder.setPaymentStatus("pending");
            adminOrder.setOrderType("dine-in");
            adminOrder.setOrderStatus("pending");
            String dineType = request.get("dineType") != null ? request.get("dineType").toString() : "alacarte";
            String noteText = "โต๊ะ " + billTableNo;
            if ("buffet".equals(dineType)) {
                noteText += " | Buffet " + buffetPax + " คน";
            }
            adminOrder.setNote(noteText);
            adminOrder.setSlipImage(slipImage);
            adminOrder.setCreatedAt(LocalDateTime.now());
            OrderEntity savedAdminOrder = orderRepository.save(adminOrder);

            String ordCode = "ORD" + String.format("%06d", savedAdminOrder.getId() * 104729L % 1000000L)
                    + savedAdminOrder.getId();
            savedAdminOrder.setOrdCode(ordCode);
            orderRepository.save(savedAdminOrder);

            for (DineInOrderEntity order : completedOrders) {
                List<DineInOrderItemEntity> items = dineInOrderItemRepository.findByOrderId(order.getId());
                for (DineInOrderItemEntity item : items) {
                    OrderItemEntity orderItem = new OrderItemEntity();
                    orderItem.setOrderId(savedAdminOrder.getId());
                    orderItem.setProductId(item.getProductId());
                    orderItem.setProductName(item.getProductName());
                    orderItem.setPrice(item.getPrice());
                    orderItem.setQuantity(item.getQuantity());
                    orderItem.setSelectedOption(item.getSelectedOption());
                    orderItem.setImage(item.getImage());
                    orderItemRepository.save(orderItem);
                }
            }

            notifyClients();

            response.put("success", true);
            response.put("message", "ส่งสลิปเรียบร้อยแล้ว");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ✅ User ดูออเดอร์ของตัวเอง
    @GetMapping("/my-orders")
    public ResponseEntity<?> getMyOrders(@RequestParam(required = false) String tableNo) {
        try {
            String email = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();

            List<DineInOrderEntity> filtered = getActiveOrders(email, tableNo);

            List<Map<String, Object>> result = new ArrayList<>();
            for (DineInOrderEntity order : filtered) {
                List<DineInOrderItemEntity> items = dineInOrderItemRepository.findByOrderId(order.getId());
                List<Map<String, Object>> itemList = new ArrayList<>();
                for (DineInOrderItemEntity item : items) {
                    Map<String, Object> i = new HashMap<>();
                    i.put("id", item.getId());
                    i.put("productId", item.getProductId());
                    i.put("productName", item.getProductName());
                    i.put("price", item.getPrice());
                    i.put("quantity", item.getQuantity());
                    i.put("selectedOption", item.getSelectedOption());
                    i.put("image", item.getImage());
                    itemList.add(i);
                }
                Map<String, Object> o = new HashMap<>();
                o.put("id", order.getId());
                o.put("ordCode", order.getOrdCode());
                o.put("orderStatus", order.getOrderStatus());
                o.put("tableNo", order.getTableNo());
                o.put("total", order.getTotal());
                o.put("subtotal", order.getSubtotal());
                o.put("createdAt", order.getCreatedAt().toString());
                o.put("items", itemList);
                o.put("paymentStatus", order.getPaymentStatus());
                result.add(o);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    // ✅ Admin ดูทุกออเดอร์
    @GetMapping("/admin/orders")
    public ResponseEntity<?> getAllOrders() {
        List<DineInOrderEntity> orders = dineInOrderRepository.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DineInOrderEntity order : orders) {
            List<DineInOrderItemEntity> items = dineInOrderItemRepository.findByOrderId(order.getId());
            Map<String, Object> o = new HashMap<>();
            o.put("id", order.getId());
            o.put("ordCode", order.getOrdCode());
            o.put("email", order.getEmail());
            o.put("tableNo", order.getTableNo());
            o.put("orderStatus", order.getOrderStatus());
            o.put("total", order.getTotal());
            o.put("subtotal", order.getSubtotal());
            o.put("createdAt", order.getCreatedAt().toString());
            o.put("items", items);
            result.add(o);
        }
        return ResponseEntity.ok(result);
    }

    // ✅ Admin ดูรายละเอียดออเดอร์
    @GetMapping("/admin/orders/{id}")
    public ResponseEntity<?> getOrderDetail(@PathVariable Long id) {
        return dineInOrderRepository.findById(id).map(order -> {
            List<DineInOrderItemEntity> items = dineInOrderItemRepository.findByOrderId(id);
            Map<String, Object> response = new HashMap<>();
            Map<String, Object> o = new HashMap<>();
            o.put("id", order.getId());
            o.put("ordCode", order.getOrdCode());
            o.put("email", order.getEmail());
            o.put("tableNo", order.getTableNo());
            o.put("orderStatus", order.getOrderStatus());
            o.put("total", order.getTotal());
            o.put("subtotal", order.getSubtotal());
            o.put("createdAt", order.getCreatedAt().toString());
            response.put("order", o);
            response.put("items", items);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ✅ Admin อัพเดทสถานะ
    @PutMapping("/admin/orders/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        // ✅ FIX #3: validate สถานะ
        String newStatus = request.get("orderStatus");
        if (newStatus == null || !VALID_STATUSES.contains(newStatus)) {
            response.put("success", false);
            response.put("message", "สถานะไม่ถูกต้อง: " + newStatus
                    + " (อนุญาต: " + String.join(", ", VALID_STATUSES) + ")");
            return ResponseEntity.badRequest().body(response);
        }

        return dineInOrderRepository.findById(id).map(order -> {
            order.setOrderStatus(newStatus);
            dineInOrderRepository.save(order);

            notifyClients();

            response.put("success", true);
            return ResponseEntity.ok(response);
        }).orElseGet(() -> {
            response.put("success", false);
            response.put("message", "ไม่พบออเดอร์");
            return ResponseEntity.badRequest().body(response);
        });
    }

    // ✅ Admin ยกเลิก
    @PutMapping("/admin/orders/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        return dineInOrderRepository.findById(id).map(order -> {
            try {
                // ✅ FIX #4: ไม่ให้ยกเลิกออเดอร์ที่เสร็จแล้วหรือจ่ายแล้ว
                if ("completed".equals(order.getOrderStatus())) {
                    response.put("success", false);
                    response.put("message", "ไม่สามารถยกเลิกออเดอร์ที่เสร็จแล้ว");
                    return ResponseEntity.badRequest().body(response);
                }
                if ("paid".equals(order.getPaymentStatus())) {
                    response.put("success", false);
                    response.put("message", "ไม่สามารถยกเลิกออเดอร์ที่ชำระเงินแล้ว");
                    return ResponseEntity.badRequest().body(response);
                }

                // ✅ คืน stock เมื่อยกเลิก
                List<DineInOrderItemEntity> items = dineInOrderItemRepository.findByOrderId(id);
                try (Connection conn = dataSource.getConnection()) {
                    for (DineInOrderItemEntity item : items) {
                        String restoreStockSql = "UPDATE tb_products " +
                                "SET \"stockQuantity\" = \"stockQuantity\" + ?, \"isAvailable\" = true " +
                                "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                        try (PreparedStatement stmt = conn.prepareStatement(restoreStockSql)) {
                            stmt.setInt(1, item.getQuantity());
                            stmt.setLong(2, item.getProductId());
                            stmt.executeUpdate();
                        }
                    }
                }
                order.setOrderStatus("cancelled");
                dineInOrderRepository.save(order);

                notifyClients();

                response.put("success", true);
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                response.put("success", false);
                response.put("message", e.getMessage());
                return ResponseEntity.badRequest().body(response);
            }
        }).orElseGet(() -> {
            response.put("success", false);
            response.put("message", "ไม่พบออเดอร์");
            return ResponseEntity.badRequest().body(response);
        });
    }
}