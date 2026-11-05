package com.app.my_project.controller;

import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.common.ProductQuantityHelper;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.PaymentReconciler;
import com.app.my_project.service.PaymentStateService;
import com.app.my_project.service.StripeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

@RestController
@RequestMapping("/api/dinein")
public class DineInOrderController {

    public DineInOrderController(
            DineInOrderRepository dineInOrderRepository,
            DineInOrderItemRepository dineInOrderItemRepository,
            DataSource dataSource,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            JwtService jwtService,
            ProductQuantityHelper qtyHelper,
            StripeService stripeService,
            PaymentStateService paymentStateService,
            PaymentReconciler paymentReconciler) {
        this.dineInOrderRepository = dineInOrderRepository;
        this.dineInOrderItemRepository = dineInOrderItemRepository;
        this.dataSource = dataSource;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.jwtService = jwtService;
        this.qtyHelper = qtyHelper;
        this.stripeService = stripeService;
        this.paymentStateService = paymentStateService;
        this.paymentReconciler = paymentReconciler;
    }

    private static final Logger log = LoggerFactory.getLogger(DineInOrderController.class);

    private final DineInOrderRepository dineInOrderRepository;

    private final DineInOrderItemRepository dineInOrderItemRepository;

    private final DataSource dataSource;

    private final OrderRepository orderRepository;

    private final OrderItemRepository orderItemRepository;

    private final JwtService jwtService;

    private final ProductQuantityHelper qtyHelper;

    private final StripeService stripeService;

    private final PaymentStateService paymentStateService;

    private final PaymentReconciler paymentReconciler;

    private static final int FRESH_STOCK_VALUE = 9999;

    // ✅ FIX #3: สถานะที่อนุญาตให้ admin เปลี่ยนได้
    private static final Set<String> VALID_STATUSES = Set.of(
            "pending", "preparing", "ready", "completed");

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

                        /*
                         * ✅ stock เก็บเป็น "ชิ้น" แต่ quantity ที่ส่งมาเป็น "ออเดอร์"
                         *    เค้ก 1 ปอนด์ = 8 ชิ้น ถ้าตัดตามตัวเลขที่ส่งมาตรงๆ
                         *    จะตัดแค่ 1 ชิ้น ของเลยไม่เคยหมดจากระบบ
                         */
                        int rawQty = qtyHelper.toRawQty(quantity,
                                orderItem.getSelectedOption());

                        String updateStockSql = "UPDATE tb_products " +
                                "SET \"stockQuantity\" = GREATEST(\"stockQuantity\" - ?, 0), " +
                                "    \"isAvailable\"   = (GREATEST(\"stockQuantity\" - ?, 0) > 0) " +
                                "WHERE id = ? AND \"stockQuantity\" != " + FRESH_STOCK_VALUE;
                        try (PreparedStatement stockStmt = conn.prepareStatement(updateStockSql)) {
                            stockStmt.setInt(1, rawQty);
                            stockStmt.setInt(2, rawQty);
                            stockStmt.setLong(3, productId);
                            stockStmt.executeUpdate();
                        }
                    }
                }
            }

            response.put("success", true);
            response.put("orderId", saved.getId());
            response.put("ordCode", ordCode);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * ลูกค้ากดเรียกเก็บบิล → ออก QR ของ Stripe
     *
     * ═══ เปลี่ยนจากอัปสลิปมาเป็น PromptPay ของ Stripe ═══
     * ของเดิมรับรูปสลิปมาเก็บแล้วรอพนักงานเปิดดูด้วยตา ระบบไม่รู้เลยว่าเงินเข้าจริงไหม
     * และบิลหน้าร้านจะไม่มี PaymentIntent ทำให้คืนเงินผ่านระบบเดียวกับออนไลน์ไม่ได้
     *
     * ตอนนี้ใช้ท่อเดียวกับหน้า checkout ทั้งหมด — webhook เป็นคนยืนยันว่าจ่ายแล้ว
     * ไม่มีใครตั้ง paid เองได้ และบิลจะคืนเงินผ่าน RefundService ได้ทันที
     *
     * ═══ ยังไม่ตัด stock ตรงนี้ ═══
     * ของถูกตัดไปตอนลูกค้าสั่งที่โต๊ะแล้ว (ดู createOrder ข้างบน) และกินไปเรียบร้อย
     * บิลนี้เป็นแค่การเก็บเงิน ไม่ใช่การจอง จึงไม่จอง stock ซ้ำ
     */
    @PostMapping("/request-bill")
    @Transactional
    public ResponseEntity<?> requestBill(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();

            String tableNo = (String) request.get("tableNo");

            List<DineInOrderEntity> activeOrders = getActiveOrders(email, tableNo);

            List<DineInOrderEntity> completedOrders = new ArrayList<>();
            for (DineInOrderEntity order : activeOrders) {
                if ("completed".equals(order.getOrderStatus())) {
                    completedOrders.add(order);
                }
            }

            if (completedOrders.isEmpty()) {
                response.put("success", false);
                response.put("message", "ไม่มีออเดอร์ที่เสร็จแล้วสำหรับเรียกเก็บเงิน");
                return ResponseEntity.badRequest().body(response);
            }

            /*
             * ─── กดเรียกบิลซ้ำ ใช้แถวเดิมเขียนทับ ───
             *
             * เดิมปล่อยใบเก่าทิ้งแล้วสร้างใบใหม่ทุกครั้ง กดสามรอบได้สามแถว
             * เปลืองเลข order และทิ้งแถว expired ที่ไม่เคยมีเงินไหลผ่านไว้เต็มตาราง
             *
             * ตรรกะเดียวกับ PaymentStateService.prepareForNewIntent ฝั่งออนไลน์
             * แถวที่ยังไม่จ่ายของโต๊ะนี้มีได้แถวเดียวเท่านั้น
             */
            // บิลที่จ่ายแล้วแต่ webhook ยังไม่มา จะถูกบันทึกเป็น paid และไม่ถูกเขียนทับ
            Set<Long> locked = paymentReconciler.settleAwaiting(email, "dine-in");
            OrderEntity reusable = findReusableBill(email, locked);
            if (reusable != null) {
                // QR เดิมต้องตาย ไม่งั้นลูกค้ามีสอง QR ที่จ่ายได้ทั้งคู่
                if (reusable.getStripePaymentIntentId() != null) {
                    stripeService.cancelIntent(reusable.getStripePaymentIntentId());
                }
                orderItemRepository.deleteByOrderId(reusable.getId());
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

            OrderEntity adminOrder = (reusable != null) ? reusable : new OrderEntity();
            adminOrder.setEmail(email);
            adminOrder.setSubtotal(grandSubtotal);
            adminOrder.setShipping(0.0);
            adminOrder.setTotal(grandTotal);
            adminOrder.setPaymentMethod("qr_promptpay");
            // ✅ awaiting_payment เท่านั้น — webhook เป็นคนเดียวที่ตั้ง paid ได้
            adminOrder.setPaymentStatus("awaiting_payment");
            adminOrder.setOrderType("dine-in");
            adminOrder.setOrderStatus("pending");

            String dineType = request.get("dineType") != null ? request.get("dineType").toString() : "alacarte";

            adminOrder.setDineType(dineType);
            if ("buffet".equals(dineType)) {
                adminOrder.setBuffetPax(buffetPax);
                adminOrder.setBuffetPricePerPerson(buffetPricePerPerson);
            }

            String noteText = "โต๊ะ " + billTableNo;
            if ("buffet".equals(dineType)) {
                noteText += " | Buffet " + buffetPax + " คน";
            }
            adminOrder.setNote(noteText);
            adminOrder.setStripePaymentIntentId(null);
            adminOrder.setCreatedAt(LocalDateTime.now());
            OrderEntity savedAdminOrder = orderRepository.save(adminOrder);

            // เลขบิลคงเดิมถ้าเป็นแถวที่ใช้ซ้ำ ลูกค้าจะได้ไม่งงว่าทำไมเลขเปลี่ยน
            String ordCode = savedAdminOrder.getOrdCode();
            if (ordCode == null || ordCode.isBlank()) {
                ordCode = "ORD" + String.format("%06d", savedAdminOrder.getId() * 104729L % 1000000L)
                        + savedAdminOrder.getId();
                savedAdminOrder.setOrdCode(ordCode);
                orderRepository.save(savedAdminOrder);
            }

            // ─── ผูกออเดอร์โต๊ะเข้ากับบิล แล้วคัดลอกรายการไปไว้ใน tb_order_items ───
            for (DineInOrderEntity order : completedOrders) {
                order.setBillOrderId(savedAdminOrder.getId());
                order.setPaymentStatus("awaiting_payment");
                dineInOrderRepository.save(order);

                List<DineInOrderItemEntity> items = dineInOrderItemRepository.findByOrderId(order.getId());
                for (DineInOrderItemEntity item : items) {
                    OrderItemEntity orderItem = new OrderItemEntity();
                    orderItem.setOrderId(savedAdminOrder.getId());
                    orderItem.setProductId(item.getProductId());
                    orderItem.setProductName(item.getProductName());
                    orderItem.setPrice(item.getPrice());
                    /*
                     * ✅ tb_order_items ทั้งระบบเก็บ quantity เป็น "ชิ้น"
                     *    ฝั่งออนไลน์เก็บ 8 สำหรับเค้ก 1 ปอนด์ แต่ฝั่งโต๊ะเก็บ 1
                     *    ถ้าคัดลอกมาตรงๆ splitQty() ที่หน้าเว็บจะหาร 8 ได้ 0
                     *    แล้วโชว์ว่ารายการนั้นถูกยกเลิกทั้งที่ลูกค้าจ่ายเต็ม
                     */
                    orderItem.setQuantity(qtyHelper.toRawQty(
                            item.getQuantity(), item.getSelectedOption()));
                    orderItem.setSelectedOption(item.getSelectedOption());
                    orderItem.setImage(item.getImage());
                    orderItemRepository.save(orderItem);
                }
            }

            // ─── ออก QR ───
            StripeService.PromptPayQr qr = stripeService.createPromptPayIntent(
                    savedAdminOrder, grandTotal, email);

            savedAdminOrder.setStripePaymentIntentId(qr.paymentIntentId());
            orderRepository.save(savedAdminOrder);

            response.put("success", true);
            response.put("orderId", savedAdminOrder.getId());
            response.put("ordCode", ordCode);
            response.put("amount", grandTotal);
            response.put("qrImageUrl", qr.qrImageUrl());
            response.put("hostedInstructionsUrl",
                    qr.hostedInstructionsUrl() == null ? "" : qr.hostedInstructionsUrl());
            response.put("paymentIntentId", qr.paymentIntentId());
            response.put("testMode", stripeService.isTestMode());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("เรียกเก็บบิลไม่สำเร็จ", e);
            response.put("success", false);
            response.put("message", "เรียกเก็บบิลไม่สำเร็จ กรุณาลองใหม่");
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * หาบิลหน้าร้านของลูกค้าคนนี้ที่ยังไม่จ่าย เพื่อเอามาเขียนทับ
     *
     * ═══ ทำไมไม่ดูจาก bill_order_id ═══
     * ตอนกดยกเลิกการชำระเงิน ระบบปลดล็อกออเดอร์โต๊ะเพื่อให้เรียกบิลใหม่ได้
     * รอบก่อนผมล้าง bill_order_id ทิ้งไปด้วย เส้นเชื่อมจึงขาด
     * พอกดเรียกบิลใหม่ก็หาแถวเดิมไม่เจอ แล้วสร้างแถวใหม่ทุกครั้ง
     *
     * ค้นจาก email + order_type + สถานะที่ยังไม่จ่ายแทน ไม่ต้องพึ่งเส้นเชื่อม
     * ตรรกะเดียวกับ PaymentStateService.prepareForNewIntent ฝั่งออนไลน์
     *
     * กดยกเลิกกี่ร้อยครั้งก็ยังเป็นแถวเดิมแถวเดียว
     */
    private OrderEntity findReusableBill(String email, Set<Long> locked) {
        List<OrderEntity> unpaid = new ArrayList<>();
        unpaid.addAll(orderRepository.findByEmailAndPaymentStatus(email, "awaiting_payment"));
        unpaid.addAll(orderRepository.findByEmailAndPaymentStatus(email, "expired"));
        unpaid.addAll(orderRepository.findByEmailAndPaymentStatus(email, "failed"));

        OrderEntity latest = null;
        for (OrderEntity o : unpaid) {
            if (!"dine-in".equals(o.getOrderType()) || locked.contains(o.getId()))
                continue;
            if (latest == null || o.getId() > latest.getId()) {
                latest = o;
            }
        }
        return latest;
    }

    /**
     * ลูกค้ากดยกเลิกการชำระเงิน หรือปิดหน้าไปเอง
     *
     * ปล่อยบิลคืนแล้วยกเลิก QR กับ Stripe ออเดอร์โต๊ะจะถูกปลดล็อกให้กดเรียกบิลใหม่ได้
     * stock ไม่ถูกคืน เพราะของกินไปแล้ว (ดู PaymentStateService.restoreStock)
     */
    @PostMapping("/bill/cancel/{orderId}")
    public ResponseEntity<?> cancelBill(@PathVariable Long orderId) {
        String email = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty() || !opt.get().getEmail().equals(email)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "ไม่พบบิล"));
        }

        OrderEntity bill = opt.get();
        if ("paid".equals(bill.getPaymentStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "บิลนี้ชำระเงินแล้ว"));
        }

        // ลูกค้าอาจสแกนจ่ายไปแล้วก่อนกดยกเลิก — ห้ามปล่อยบิลที่ได้เงินแล้ว
        if (paymentReconciler.settle(bill) != PaymentReconciler.Outcome.UNPAID) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "paid", true, "message", "บิลนี้ชำระเงินแล้ว"));
        }
        paymentStateService.releaseOrder(orderId, PaymentStateService.EXPIRED, "ลูกค้ายกเลิกการชำระเงิน");

        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * หน้าบิล poll ถามสถานะ — อย่าเชื่อฝั่ง client ว่าจ่ายแล้ว
     *
     * ตัวเดียวที่เปลี่ยนสถานะเป็น paid ได้คือ webhook
     */
    @GetMapping("/bill/status/{orderId}")
    public ResponseEntity<?> billStatus(@PathVariable Long orderId) {
        String email = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty() || !opt.get().getEmail().equals(email)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "ไม่พบบิล"));
        }

        OrderEntity bill = opt.get();
        if (paymentReconciler.refresh(bill) == PaymentReconciler.Outcome.PAID) {
            bill = orderRepository.findById(orderId).orElse(bill);
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "orderId", bill.getId(),
                "ordCode", bill.getOrdCode() == null ? "" : bill.getOrdCode(),
                "paymentStatus", bill.getPaymentStatus() == null ? "" : bill.getPaymentStatus(),
                "paid", "paid".equals(bill.getPaymentStatus())));
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
                        /*
                         * ✅ คืนเป็น "ชิ้น" ให้ตรงกับที่ตัดไปตอนสั่ง
                         *    ตอนสั่งตัด 8 ชิ้นสำหรับเค้ก 1 ปอนด์ ถ้าคืนแค่ 1
                         *    ของจะหายจากระบบ 7 ชิ้นทุกครั้งที่ยกเลิก
                         */
                        int restoreQty = qtyHelper.toRawQty(
                                item.getQuantity(), item.getSelectedOption());

                        try (PreparedStatement stmt = conn.prepareStatement(restoreStockSql)) {
                            stmt.setInt(1, restoreQty);
                            stmt.setLong(2, item.getProductId());
                            stmt.executeUpdate();
                        }
                    }
                }
                order.setOrderStatus("cancelled");
                dineInOrderRepository.save(order);

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

    // ✅ Admin ลบข้อมูล dine-in ทั้งหมด
    // ลบเฉพาะ tb_dinein_orders + tb_dinein_order_items เท่านั้น
    // ไม่แตะ tb_orders / tb_order_items → หน้า Manage orders และ Top Products
    // ไม่ได้รับผลกระทบ
    @DeleteMapping("/admin/orders/all")
    @Transactional
    public ResponseEntity<?> deleteAllDineIn(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Map<String, Object> response = new HashMap<>();

        // กันไว้: ต้องเป็น admin เท่านั้น (endpoint นี้ทำลายข้อมูลถาวร)
        if (!jwtService.isAdmin(auth)) {
            response.put("success", false);
            response.put("message", "ต้องเป็นผู้ดูแลระบบเท่านั้น");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            long itemCount = dineInOrderItemRepository.count();
            long orderCount = dineInOrderRepository.count();

            // ลบตารางลูกก่อนตารางแม่ เพื่อกัน foreign key constraint
            dineInOrderItemRepository.deleteAllInBatch();
            dineInOrderRepository.deleteAllInBatch();

            log.info("Admin ลบข้อมูล dine-in ทั้งหมด: {} orders, {} items", orderCount, itemCount);

            response.put("success", true);
            response.put("deletedOrders", orderCount);
            response.put("deletedItems", itemCount);
            response.put("message", "ลบข้อมูล dine-in ทั้งหมดแล้ว");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("ลบข้อมูล dine-in ไม่สำเร็จ", e);
            response.put("success", false);
            response.put("message", "ลบไม่สำเร็จ: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}