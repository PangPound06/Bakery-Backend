package com.app.my_project.controller;

import com.app.my_project.common.CheckoutMethod;
import com.app.my_project.common.ProductQuantityHelper;
import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.service.DineInBillService;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.PaymentStarter;
import com.app.my_project.service.PaymentStateService;
import com.app.my_project.service.PricingService;
import com.app.my_project.service.StripeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * ทานในร้าน — สั่งอาหารเข้าครัว เรียกเก็บบิล และหน้าจัดการของแอดมิน
 *
 * ตรรกะสั่งอาหาร/ออกบิลอยู่ที่ DineInBillService
 * หลังออกบิลแล้ว หน้าเว็บใช้ /api/checkout/status | cancel | transfer/{id}/submit
 * ร่วมกับฝั่งออนไลน์ (panel จ่ายเงินชุดเดียวกัน)
 */
@RestController
@RequestMapping("/api/dinein")
public class DineInOrderController {

    public DineInOrderController(
            DineInOrderRepository dineInOrderRepository,
            DineInOrderItemRepository dineInOrderItemRepository,
            DataSource dataSource,
            JwtService jwtService,
            ProductQuantityHelper qtyHelper,
            StripeService stripeService,
            PaymentStateService paymentStateService,
            DineInBillService billService,
            PaymentStarter paymentStarter) {
        this.dineInOrderRepository = dineInOrderRepository;
        this.dineInOrderItemRepository = dineInOrderItemRepository;
        this.dataSource = dataSource;
        this.jwtService = jwtService;
        this.qtyHelper = qtyHelper;
        this.stripeService = stripeService;
        this.paymentStateService = paymentStateService;
        this.billService = billService;
        this.paymentStarter = paymentStarter;
    }

    private static final Logger log = LoggerFactory.getLogger(DineInOrderController.class);

    private final DineInOrderRepository dineInOrderRepository;

    private final DineInOrderItemRepository dineInOrderItemRepository;

    private final DataSource dataSource;

    private final JwtService jwtService;

    private final ProductQuantityHelper qtyHelper;

    private final StripeService stripeService;

    private final PaymentStateService paymentStateService;

    private final DineInBillService billService;

    private final PaymentStarter paymentStarter;

    private static final int FRESH_STOCK_VALUE = 9999;

    // ✅ FIX #3: สถานะที่อนุญาตให้ admin เปลี่ยนได้
    private static final Set<String> VALID_STATUSES = Set.of(
            "pending", "preparing", "ready", "completed");

    private static String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /**
     * ส่งตะกร้าทานในร้านเข้าครัว
     *
     * body: { tableNo } — รายการและราคามาจากตะกร้าทานในร้านของลูกค้า (คิดราคาที่ server)
     */
    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> request) {
        try {
            DineInOrderEntity saved = billService.placeTableOrder(currentEmail(), asString(request.get("tableNo")));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "orderId", saved.getId(),
                    "ordCode", saved.getOrdCode(),
                    "total", saved.getTotal()));
        } catch (DineInBillService.BillException e) {
            return fail(e.status(), e.getMessage());
        } catch (PricingService.CartValidationException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * ลูกค้ากดเรียกเก็บบิล → เตรียมบิลแล้วออกช่องทางจ่ายเงินตามที่เลือก
     *
     * body: { tableNo, dineType: alacarte|buffet, buffetPax, paymentMethod: promptpay|card|transfer }
     * ยอดเงินคิดที่ server ทั้งหมด (DineInBillService) — ไม่รับราคาจาก client
     *
     * ไม่ตัด stock ตรงนี้ — ของถูกตัดตอนสั่งที่โต๊ะและกินไปแล้ว บิลเป็นแค่การเก็บเงิน
     */
    @PostMapping("/request-bill")
    public ResponseEntity<?> requestBill(@RequestBody Map<String, Object> request) {
        String email = currentEmail();

        // ไม่ส่งวิธีจ่ายมา = QR ของ Stripe (พฤติกรรมเดิม)
        String rawMethod = asString(request.get("paymentMethod"));
        Optional<CheckoutMethod> method = CheckoutMethod.parse(rawMethod == null ? "promptpay" : rawMethod);
        if (method.isEmpty())
            return fail(HttpStatus.BAD_REQUEST, "กรุณาเลือกวิธีชำระเงิน");
        if (method.get().viaStripe() && !stripeService.isEnabled())
            return fail(HttpStatus.SERVICE_UNAVAILABLE, "ระบบชำระเงินยังไม่พร้อมใช้งาน");

        int pax;
        try {
            pax = request.get("buffetPax") == null ? 1 : Integer.parseInt(request.get("buffetPax").toString());
        } catch (NumberFormatException e) {
            return fail(HttpStatus.BAD_REQUEST, "จำนวนคนไม่ถูกต้อง");
        }

        OrderEntity bill;
        try {
            bill = billService.prepareBill(email, asString(request.get("tableNo")),
                    DineInBillService.DineType.parse(asString(request.get("dineType"))), pax, method.get());
        } catch (DineInBillService.BillException e) {
            return fail(e.status(), e.getMessage());
        }

        try {
            Map<String, Object> resp = new HashMap<>(
                    paymentStarter.start(bill, bill.getTotal(), email, method.get()));
            resp.put("success", true);
            resp.put("method", method.get().requestValue());
            resp.put("orderId", bill.getId());
            resp.put("ordCode", bill.getOrdCode());
            resp.put("total", bill.getTotal());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("ออกช่องทางจ่ายเงินของบิล {} ไม่สำเร็จ", bill.getId(), e);
            paymentStateService.discardUnpaid(bill.getId(), "เริ่มการชำระเงินไม่สำเร็จ");
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "เรียกเก็บบิลไม่สำเร็จ กรุณาลองใหม่");
        }
    }

    private static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "message", message));
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString().trim();
    }

    // ✅ User ดูออเดอร์ของตัวเอง
    @GetMapping("/my-orders")
    public ResponseEntity<?> getMyOrders(@RequestParam(required = false) String tableNo) {
        try {
            List<DineInOrderEntity> filtered = billService.activeTableOrders(currentEmail(), tableNo);

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