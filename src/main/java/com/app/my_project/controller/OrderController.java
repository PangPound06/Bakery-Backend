package com.app.my_project.controller;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.entity.ProductEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.ProductRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private ProductRepository productRepository;

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

            // ✅ รับ slipImage (path ของสลิปที่อัพโหลด)
            if (request.get("slipImage") != null) {
                order.setSlipImage((String) request.get("slipImage"));
            }

            order.setCardName((String) request.get("cardName"));
            order.setCardLast4((String) request.get("cardLast4"));

            // ข้อมูลการจัดส่ง
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

            // 2. บันทึก Items + ตัด Stock
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");

            if (items != null) {
                for (Map<String, Object> item : items) {
                    Long productId = Long.parseLong(item.get("productId").toString());
                    int quantity = Integer.parseInt(item.get("quantity").toString());

                    // บันทึก OrderItem
                    OrderItemEntity orderItem = new OrderItemEntity();
                    orderItem.setOrderId(savedOrder.getId());
                    orderItem.setProductId(productId);
                    orderItem.setProductName((String) item.get("productName"));
                    orderItem.setPrice(Double.parseDouble(item.get("price").toString()));
                    orderItem.setQuantity(quantity);
                    orderItemRepository.save(orderItem);

                    // ✅ ตัด Stock
                    Optional<ProductEntity> productOpt = productRepository.findById(productId);
                    if (productOpt.isPresent()) {
                        ProductEntity product = productOpt.get();
                        Long currentStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0L;
                        long newStock = currentStock - quantity;
                        if (newStock < 0)
                            newStock = 0;

                        product.setStockQuantity(newStock);
                        product.setIsAvailable(newStock > 0);
                        productRepository.save(product);

                        System.out.println(
                                "✅ Stock updated: " + product.getName() + " (" + currentStock + " → " + newStock + ")");
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
    @GetMapping("/all")
    public ResponseEntity<?> getAllOrders() {
        List<OrderEntity> orders = orderRepository.findAllByOrderByCreatedAtDesc();
        return ResponseEntity.ok(orders);
    }

    // ยกเลิก Order + คืน Stock
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

            // ✅ คืน Stock
            List<OrderItemEntity> items = orderItemRepository.findByOrderId(id);
            for (OrderItemEntity item : items) {
                Optional<ProductEntity> productOpt = productRepository.findById(item.getProductId());
                if (productOpt.isPresent()) {
                    ProductEntity product = productOpt.get();
                    Long currentStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0L;
                    product.setStockQuantity(currentStock + item.getQuantity());
                    product.setIsAvailable(true);
                    productRepository.save(product);

                    System.out.println("✅ Stock restored: " + product.getName());
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
}