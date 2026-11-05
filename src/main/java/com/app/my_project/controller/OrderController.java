package com.app.my_project.controller;

import com.app.my_project.common.CurrentUser;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.service.OrderItemService;
import com.app.my_project.service.OrderService;
import com.app.my_project.service.OrderStatsService;
import com.app.my_project.service.PaymentReconciler;
import com.app.my_project.service.PaymentStateService;
import com.app.my_project.service.RefundService;
import com.app.my_project.service.StripeService;
import com.app.my_project.service.StockService;
import com.app.my_project.service.StoreSettingService;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * OrderController — refactored from 1042 lines → ~280 lines
 *
 * Pattern:
 * - Thin controller layer: ทุก endpoint แค่
 * 1. ตรวจ auth (admin/owner)
 * 2. parse request → DTO ของ service
 * 3. เรียก service method
 * 4. map result enum → HTTP status
 * - ทุก business logic อยู่ใน service layer
 * - ไม่มี raw JDBC, ไม่มี @Transactional ที่ controller
 *
 * Endpoints:
 * - POST /                              (แอดมิน POS / ลูกค้า)
 * - GET /user/{email}, GET /{id}        (เจ้าของ order หรือแอดมิน)
 * - PUT /{id}/cancel                    (เจ้าของ order หรือแอดมิน)
 * - GET /search/{orderCode}             (สาธารณะ — หน้าค้นหาคำสั่งซื้อด้วยเลข ORD)
 * - GET /my-dine-in                     (ลูกค้า)
 * - GET /stats/top-products             (สาธารณะ)
 * - PUT /{id}/status, GET /all, PUT /backfill-ordcode,
 *   POST /{id}/transfer/confirm, POST /{id}/transfer/reject,
 *   POST|PATCH|DELETE /{orderId}/items…  (แอดมิน)
 *
 * CORS ตั้งที่ SecurityConfig ที่เดียว
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final OrderStatsService orderStatsService;
    private final OrderItemRepository orderItemRepository;
    private final StockService stockService;
    private final CurrentUser currentUser;
    private final StoreSettingService storeSettingService;
    private final RefundService refundService;
    private final PaymentStateService paymentStateService;
    private final PaymentReconciler paymentReconciler;

    public OrderController(OrderService orderService,
            OrderItemService orderItemService,
            OrderStatsService orderStatsService,
            OrderItemRepository orderItemRepository,
            StockService stockService,
            CurrentUser currentUser,
            StoreSettingService storeSettingService,
            RefundService refundService,
            PaymentStateService paymentStateService,
            PaymentReconciler paymentReconciler) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.orderStatsService = orderStatsService;
        this.orderItemRepository = orderItemRepository;
        this.stockService = stockService;
        this.currentUser = currentUser;
        this.storeSettingService = storeSettingService;
        this.refundService = refundService;
        this.paymentStateService = paymentStateService;
        this.paymentReconciler = paymentReconciler;
    }

    // ═════════════════════════════════════════════════════════════════
    // CREATE
    // ═════════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════
     * ⚠️ endpoint นี้เหลือไว้สำหรับ POS ของแอดมินเท่านั้น
     *
     * ลูกค้าที่ชำระเงินจริงต้องไปทาง POST /api/checkout/session แทน
     * (ดู CheckoutController) เพราะที่นั่นราคาถูกคำนวณฝั่ง server
     *
     * ถ้าคนเรียกไม่ใช่แอดมิน:
     * - email ถูกบังคับเป็นของเจ้าของ token ไม่ใช่ค่าใน body
     * - paymentStatus ถูกบังคับเป็น "pending" เสมอ
     * ตั้ง "paid" เองไม่ได้อีกต่อไป
     * ═══════════════════════════════════════════════════════════════
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        boolean admin = currentUser.isAdmin(auth);
        String email;

        if (admin) {
            // แอดมินสร้างออเดอร์แทนลูกค้าได้ (หน้าขายหน้าร้าน)
            email = asString(body.get("email"));
        } else {
            // ลูกค้าทั่วไป — เอา email จาก token เท่านั้น
            Optional<String> customer = currentUser.customerEmail(auth);
            if (customer.isEmpty())
                return unauthorized();
            email = customer.get();
        }

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email required"));
        }

        try {
            OrderService.CreateOrderRequest req = parseCreateRequest(body);

            // ✅ ไม่ใช่แอดมิน → ห้ามตั้งสถานะจ่ายเงินเอง
            if (!admin) {
                req = forcePending(req);
            }

            // กันสั่งออเดอร์ออนไลน์ตอนร้านปิดรับ (ไม่กระทบ pos / dine-in)
            if ("online".equalsIgnoreCase(req.orderType())
                    && !storeSettingService.isOnlineOrderingOpen()) {
                StoreSettingEntity st = storeSettingService.getStatus();
                String msg = (st.getClosedMessage() != null && !st.getClosedMessage().isBlank())
                        ? st.getClosedMessage()
                        : "ขณะนี้ร้านปิดรับออเดอร์ออนไลน์ชั่วคราว";
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("error", msg);
                resp.put("storeClosed", true);
                resp.put("reopenAt", st.getReopenAt() == null ? "" : st.getReopenAt());
                return ResponseEntity.status(403).body(resp);
            }

            OrderEntity saved = orderService.createOrder(email, req);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "orderId", saved.getId(),
                    "ordCode", saved.getOrdCode()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * บังคับให้ออเดอร์ที่ลูกค้าสร้างเองเป็น "pending" เสมอ
     * และล้างข้อมูลบัตรที่อาจแอบส่งมา
     */
    private OrderService.CreateOrderRequest forcePending(OrderService.CreateOrderRequest r) {
        return new OrderService.CreateOrderRequest(
                r.subtotal(), r.shipping(), r.total(),
                r.paymentMethod(),
                "pending", // ← ไม่ว่า client ส่งอะไรมา
                null, // ← paymentId ต้องมาจาก payment gateway เท่านั้น
                r.orderType(), r.orderStatus(), r.slipImage(),
                null, null, // ← ไม่รับข้อมูลบัตรจาก client อีกต่อไป
                r.receiverName(), r.receiverPhone(), r.receiverAddress(), r.note(),
                r.items());
    }

    @SuppressWarnings("unchecked")
    private OrderService.CreateOrderRequest parseCreateRequest(Map<String, Object> body) {
        List<OrderService.OrderItemRequest> items = new ArrayList<>();
        Object itemsObj = body.get("items");
        if (itemsObj instanceof List<?> itemsList) {
            for (Object obj : itemsList) {
                if (obj instanceof Map<?, ?> itemMap) {
                    Map<String, Object> m = (Map<String, Object>) itemMap;
                    items.add(new OrderService.OrderItemRequest(
                            asLong(m.get("productId")),
                            asString(m.get("productName")),
                            asDouble(m.get("price")),
                            asInt(m.get("quantity")),
                            asString(m.get("selectedOption")),
                            asString(m.get("image"))));
                }
            }
        }

        return new OrderService.CreateOrderRequest(
                asDouble(body.get("subtotal")),
                asDouble(body.get("shipping")),
                asDouble(body.get("total")),
                asString(body.get("paymentMethod")),
                asString(body.get("paymentStatus")),
                asString(body.get("paymentId")),
                asString(body.get("orderType")),
                asString(body.get("orderStatus")),
                asString(body.get("slipImage")),
                asString(body.get("cardName")),
                asString(body.get("cardLast4")),
                asString(body.get("receiverName")),
                asString(body.get("receiverPhone")),
                asString(body.get("receiverAddress")),
                asString(body.get("note")),
                items);
    }

    // ═════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════

    /** order ทั้งหมดของลูกค้าหนึ่งคน — มีชื่อ เบอร์ ที่อยู่ จึงให้ดูได้แค่เจ้าของกับแอดมิน */
    @GetMapping("/user/{email}")
    public ResponseEntity<?> getByEmail(@PathVariable String email,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!currentUser.isAdminOrOwner(auth, email))
            return forbidden();
        return ResponseEntity.ok(
                pageEnvelope(orderService.getByEmail(email, parsePageable(page, size))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // order ของคนอื่นตอบ 404 เหมือนไม่มีอยู่ — ไม่บอกว่า id นี้มีจริง
        Optional<OrderEntity> opt = orderService.getById(id)
                .filter(o -> currentUser.isAdminOrOwner(auth, o.getEmail()));
        if (opt.isEmpty())
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detailResponse(opt.get(), true));
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAll(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!currentUser.isAdmin(auth))
            return forbidden();

        return ResponseEntity.ok(
                pageEnvelope(orderService.getAll(parsePageable(page, size))));
    }

    @GetMapping("/search/{orderCode}")
    public ResponseEntity<?> searchByCode(@PathVariable String orderCode,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<OrderEntity> opt = orderService.searchByCode(orderCode);
        if (opt.isEmpty())
            return ResponseEntity.notFound().build();

        // ใครรู้เลข ORD ก็ดูสถานะได้ แต่ข้อมูลติดต่อกับสลิปให้เห็นเฉพาะเจ้าของและแอดมิน
        OrderEntity order = opt.get();
        boolean privileged = currentUser.isAdminOrOwner(auth, order.getEmail());
        return ResponseEntity.ok(detailResponse(order, privileged));
    }

    /** 0812345678 → xxxxxx5678 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4)
            return phone;
        return "x".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }

    @GetMapping("/my-dine-in")
    public ResponseEntity<?> getMyDineIn(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Optional<String> email = currentUser.customerEmail(auth);
        if (email.isEmpty())
            return unauthorized();

        return ResponseEntity.ok(
                pageEnvelope(orderService.getDineInByEmail(email.get(), parsePageable(page, size))));
    }

    // ═════════════════════════════════════════════════════════════════
    // UPDATE
    // ═════════════════════════════════════════════════════════════════

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();

        String orderStatus = asString(body.get("orderStatus"));
        String paymentStatus = asString(body.get("paymentStatus"));
        return switch (orderService.updateStatus(id, orderStatus, paymentStatus)) {
            case SUCCESS -> ResponseEntity.ok(Map.of("success", true));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case USE_CANCEL -> ResponseEntity.badRequest()
                    .body(Map.of("error", "ยกเลิกคำสั่งซื้อผ่านปุ่มยกเลิก เพื่อให้ระบบคืน stock"));
            case PAYMENT_REQUIRED -> ResponseEntity.status(409)
                    .body(Map.of("error", "คำสั่งซื้อออนไลน์นี้ยังไม่ได้รับชำระเงิน"));
        };
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<OrderEntity> opt = orderService.getById(id)
                .filter(o -> currentUser.isAdminOrOwner(auth, o.getEmail()));
        if (opt.isEmpty())
            return ResponseEntity.notFound().build();

        // ยังรอจ่ายผ่าน Stripe อยู่ → ปิด QR/ฟอร์มบัตรก่อน ไม่งั้นลูกค้ายังจ่ายเข้ามาได้หลังยกเลิก
        OrderEntity order = opt.get();
        if (PaymentStateService.AWAITING.equals(order.getPaymentStatus())) {
            if (paymentReconciler.settle(order) != PaymentReconciler.Outcome.UNPAID)
                return paidRequiresRefund();
            paymentStateService.releaseOrder(id, PaymentStateService.EXPIRED, "ยกเลิกคำสั่งซื้อ");
            return ResponseEntity.ok(Map.of("success", true));
        }

        return switch (orderService.cancelOrder(id)) {
            case SUCCESS -> ResponseEntity.ok(Map.of("success", true));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_DELIVERED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot cancel delivered order"));
            // ✅ order ที่จ่ายเงินแล้ว ต้องผ่านระบบขอคืนเงิน
            case PAID_REQUIRES_REFUND -> paidRequiresRefund();
            case AWAITING_VERIFICATION -> ResponseEntity.status(409)
                    .body(Map.of("error", "ลูกค้าแจ้งโอนแล้ว ต้องยืนยันหรือปฏิเสธยอดโอนก่อน"));
        };
    }

    // ═════════════════════════════════════════════════════════════════
    // PROMPTPAY TRANSFER (admin only) — ตรวจยอดในแอปธนาคารของร้านแล้วกดตัดสิน
    // ═════════════════════════════════════════════════════════════════

    @PostMapping("/{id}/transfer/confirm")
    public ResponseEntity<?> confirmTransfer(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<String> admin = currentUser.adminEmail(auth);
        if (admin.isEmpty())
            return forbidden();
        return transferResponse(paymentStateService.confirmTransfer(id, admin.get()));
    }

    @PostMapping("/{id}/transfer/reject")
    public ResponseEntity<?> rejectTransfer(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<String> admin = currentUser.adminEmail(auth);
        if (admin.isEmpty())
            return forbidden();
        String reason = body == null ? null : asString(body.get("reason"));
        return transferResponse(paymentStateService.rejectTransfer(id, reason, admin.get()));
    }

    private ResponseEntity<?> transferResponse(PaymentStateService.TransferResult result) {
        return switch (result) {
            case OK -> ResponseEntity.ok(Map.of("success", true));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NOT_TRANSFER -> ResponseEntity.badRequest()
                    .body(Map.of("error", "คำสั่งซื้อนี้ไม่ได้ชำระด้วยการโอนพร้อมเพย์"));
            case WRONG_STATE, INVALID_SLIP -> ResponseEntity.status(409)
                    .body(Map.of("error", "คำสั่งซื้อนี้ถูกยืนยัน ปฏิเสธ หรือหมดเวลาไปแล้ว"));
        };
    }

    private static ResponseEntity<?> paymentInProgress() {
        return ResponseEntity.status(409)
                .body(Map.of("error", "ลูกค้ากำลังชำระเงินหรือรอตรวจยอดโอน แก้ไขรายการไม่ได้"));
    }

    private static ResponseEntity<?> paidRequiresRefund() {
        return ResponseEntity.status(409)
                .body(Map.of("error", "คำสั่งซื้อนี้ชำระเงินแล้ว ต้องดำเนินการผ่านระบบขอคืนเงิน (/api/cancel-requests)",
                        "requiresRefund", true));
    }

    @PutMapping("/backfill-ordcode")
    public ResponseEntity<?> backfillOrdCodes(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();
        int count = orderService.backfillOrdCodes();
        return ResponseEntity.ok(Map.of("success", true, "updatedCount", count));
    }

    // ═════════════════════════════════════════════════════════════════
    // ITEMS (admin only)
    // ═════════════════════════════════════════════════════════════════

    @PostMapping("/{orderId}/items")
    public ResponseEntity<?> addItem(@PathVariable Long orderId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();

        OrderItemService.AddItemRequest req = new OrderItemService.AddItemRequest(
                asLong(body.get("productId")),
                asString(body.get("productName")),
                asDouble(body.get("price")),
                asInt(body.get("quantity")), // ✅ frontend ส่ง "quantity"
                asString(body.get("selectedOption")),
                asString(body.get("image")));

        OrderItemService.AddItemResult result = orderItemService.addItem(orderId, req);
        return mapAddResult(result);
    }

    @PatchMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<?> updateItemQty(@PathVariable Long orderId,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();

        int qty = asInt(body.get("quantity")); // ✅ frontend ส่ง "quantity"
        OrderItemService.UpdateQtyResult result = orderItemService.updateQuantity(orderId, itemId, qty);
        return mapUpdateResult(result);
    }

    @DeleteMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<?> removeItem(@PathVariable Long orderId,
            @PathVariable Long itemId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();

        OrderItemService.RemoveItemResult result = orderItemService.removeItem(orderId, itemId);
        return mapRemoveResult(result);
    }

    // ═════════════════════════════════════════════════════════════════
    // STATS (admin only)
    // ═════════════════════════════════════════════════════════════════

    @GetMapping("/stats/top-products")
    public ResponseEntity<?> topProducts(@RequestParam(defaultValue = "all") String days,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // หน้าแรกของร้านใช้ endpoint นี้โชว์สินค้าขายดีด้วย — ใครก็เรียกได้
        // แต่ยอดขายเป็นข้อมูลธุรกิจ ให้เห็นเฉพาะแอดมิน
        boolean admin = currentUser.isAdmin(auth);
        OrderStatsService.TopProductsResult result = orderStatsService.getTopProducts(days);

        List<Map<String, Object>> top = new ArrayList<>();
        for (OrderStatsService.TopProduct p : result.topProducts()) {
            Map<String, Object> m = new HashMap<>();
            m.put("productName", p.productName());
            m.put("selectedOption", p.selectedOption() == null ? "" : p.selectedOption());
            m.put("category", p.category());
            m.put("totalQty", p.totalQty());
            m.put("orderCount", p.orderCount());
            if (admin) {
                m.put("totalRevenue", p.totalRevenue());
            }
            top.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("topProducts", top);
        body.put("totalAllQty", result.totalAllQty());
        body.put("totalAllOrders", result.totalAllOrders());
        body.put("totalProductCount", result.totalProductCount());
        if (admin) {
            body.put("totalAllRevenue", result.totalAllRevenue());
        }
        return ResponseEntity.ok(body);
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /**
     * { success, order, items } — order มียอดที่คืนเงินไปแล้วด้วย (ใช้เฉพาะหน้ารายละเอียด กัน N+1)
     *
     * @param includeContact false = ซ่อน email ที่อยู่ สลิป และเบอร์ (เหลือ 4 ตัวท้าย)
     */
    private Map<String, Object> detailResponse(OrderEntity order, boolean includeContact) {
        List<Map<String, Object>> items = loadItems(order.getId());
        Map<String, Object> orderMap = orderToMap(order, items);
        orderMap.put("refundedAmount", refundedBaht(order.getId()));
        if (!includeContact) {
            orderMap.put("email", null);
            orderMap.put("receiverPhone", maskPhone(order.getReceiverPhone()));
            orderMap.put("receiverAddress", null);
            orderMap.put("slipImage", null);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("order", orderMap);
        response.put("items", items);
        return response;
    }

    private ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }

    /** แปลง OrderItemEntity → map + เติม image จาก product ถ้า item.image == null */
    private Map<String, Object> itemToMap(OrderItemEntity item) {
        String image = item.getImage();
        if (image == null || image.isBlank()) {
            image = stockService.findImage(item.getProductId());
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", item.getId());
        m.put("productId", item.getProductId());
        m.put("productName", item.getProductName());
        m.put("price", item.getPrice());
        m.put("quantity", item.getQuantity());
        m.put("selectedOption", item.getSelectedOption());
        m.put("image", image);
        // ✅ หน้ายกเลิกบางรายการต้องรู้ว่าเหลือให้ยกเลิกได้อีกเท่าไหร่
        m.put("cancelledQty", item.getCancelledQty());
        return m;
    }

    /**
     * ยอดที่คืนเงินไปแล้วของ order นี้ (บาท)
     *
     * อ่านจาก tb_refunds อย่างเดียว ไม่ได้ยิง Stripe จึงเรียกถี่ได้
     * ใช้เฉพาะ endpoint รายละเอียด — หน้า list ไม่เรียก กัน N+1
     */
    private double refundedBaht(Long orderId) {
        return StripeService.toBaht(refundService.refundedMinorUnit(orderId));
    }

    /** ดึง items ของ order เดียว (ใช้ใน endpoint รายตัว เช่น GET /{id}) */
    private List<Map<String, Object>> loadItems(Long orderId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderItemEntity item : orderItemRepository.findByOrderId(orderId)) {
            result.add(itemToMap(item));
        }
        return result;
    }

    /**
     * ✅ โหลด items ของหลาย order พร้อมกันในครั้งเดียว (กัน N+1)
     * คืน map: orderId → list ของ item map
     */
    private Map<Long, List<Map<String, Object>>> loadItemsForOrders(List<Long> orderIds) {
        Map<Long, List<Map<String, Object>>> byOrder = new HashMap<>();
        if (orderIds.isEmpty())
            return byOrder;
        for (OrderItemEntity item : orderItemRepository.findByOrderIdIn(orderIds)) {
            byOrder.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>())
                    .add(itemToMap(item));
        }
        return byOrder;
    }

    /** จำกัด page/size ให้ปลอดภัย (size สูงสุด 100 กันดึงทีละมหาศาล) */
    private Pageable parsePageable(int page, int size) {
        int p = Math.max(page, 0);
        int s = size < 1 ? 20 : Math.min(size, 100);
        return PageRequest.of(p, s);
    }

    /** ห่อ Page<OrderEntity> เป็น response envelope + batch-load items (กัน N+1) */
    private Map<String, Object> pageEnvelope(Page<OrderEntity> orderPage) {
        List<OrderEntity> orders = orderPage.getContent();
        List<Long> ids = orders.stream().map(OrderEntity::getId).toList();
        Map<Long, List<Map<String, Object>>> itemsByOrder = loadItemsForOrders(ids);

        List<Map<String, Object>> content = new ArrayList<>();
        for (OrderEntity o : orders) {
            content.add(orderToMap(o, itemsByOrder.getOrDefault(o.getId(), new ArrayList<>())));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        body.put("page", orderPage.getNumber());
        body.put("size", orderPage.getSize());
        body.put("totalElements", orderPage.getTotalElements());
        body.put("totalPages", orderPage.getTotalPages());
        body.put("hasNext", orderPage.hasNext());
        return body;
    }

    private Map<String, Object> orderToMap(OrderEntity o, List<Map<String, Object>> items) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.getId());
        m.put("ordCode", o.getOrdCode());
        m.put("email", o.getEmail());
        m.put("subtotal", o.getSubtotal());
        m.put("shipping", o.getShipping());
        m.put("total", o.getTotal());
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("paymentStatus", o.getPaymentStatus());
        m.put("paymentId", o.getPaymentId());
        m.put("orderType", o.getOrderType());
        m.put("orderStatus", o.getOrderStatus());
        m.put("slipImage", o.getSlipImage());
        m.put("cardName", o.getCardName());
        m.put("cardLast4", o.getCardLast4());
        m.put("receiverName", o.getReceiverName());
        m.put("receiverPhone", o.getReceiverPhone());
        m.put("receiverAddress", o.getReceiverAddress());
        m.put("note", o.getNote());
        m.put("createdAt", o.getCreatedAt());
        m.put("items", items);
        return m;
    }

    private ResponseEntity<?> mapAddResult(OrderItemService.AddItemResult result) {
        return switch (result.result()) {
            case SUCCESS -> {
                // ✅ Frontend expects: { success, newItem: {full object}, newSubtotal, newTotal
                // }
                OrderItemEntity item = result.savedItem();
                Map<String, Object> newItem = new HashMap<>();
                newItem.put("id", item.getId());
                newItem.put("productId", item.getProductId());
                newItem.put("productName", item.getProductName());
                newItem.put("price", item.getPrice());
                newItem.put("quantity", item.getQuantity());
                newItem.put("selectedOption", item.getSelectedOption());
                newItem.put("image", item.getImage());

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("newItem", newItem);
                response.put("newSubtotal", result.totals().subtotal());
                response.put("newTotal", result.totals().total());
                yield ResponseEntity.ok(response);
            }
            case ORDER_NOT_FOUND -> ResponseEntity.notFound().build();
            case ORDER_LOCKED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot modify delivered/cancelled order"));
            case PAID_REQUIRES_REFUND -> paidRequiresRefund();
            case PAYMENT_IN_PROGRESS -> paymentInProgress();
            default -> ResponseEntity.badRequest().body(Map.of("error", result.result().name()));
        };
    }

    private ResponseEntity<?> mapUpdateResult(OrderItemService.UpdateQtyResult result) {
        return switch (result.result()) {
            case SUCCESS -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "newSubtotal", result.totals().subtotal(),
                    "newTotal", result.totals().total()));
            case ORDER_NOT_FOUND, ITEM_NOT_FOUND -> ResponseEntity.notFound().build();
            case ORDER_LOCKED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot modify delivered/cancelled order"));
            case ITEM_NOT_IN_ORDER -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Item does not belong to this order"));
            case PAID_REQUIRES_REFUND -> paidRequiresRefund();
            case PAYMENT_IN_PROGRESS -> paymentInProgress();
            default -> ResponseEntity.badRequest().body(Map.of("error", result.result().name()));
        };
    }

    private ResponseEntity<?> mapRemoveResult(OrderItemService.RemoveItemResult result) {
        return switch (result.result()) {
            case SUCCESS -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "newSubtotal", result.totals().subtotal(),
                    "newTotal", result.totals().total()));
            case ORDER_CANCELLED -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "cancelled", true,
                    "message", "Last item removed - order cancelled"));
            case ORDER_NOT_FOUND, ITEM_NOT_FOUND -> ResponseEntity.notFound().build();
            case ORDER_LOCKED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot modify delivered/cancelled order"));
            case ITEM_NOT_IN_ORDER -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Item does not belong to this order"));
            case PAID_REQUIRES_REFUND -> paidRequiresRefund();
            case PAYMENT_IN_PROGRESS -> paymentInProgress();
            default -> ResponseEntity.badRequest().body(Map.of("error", result.result().name()));
        };
    }

    // ─── Type conversion helpers ──────────────────────────────────

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number n)
            return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInt(Object v) {
        if (v == null)
            return 0;
        if (v instanceof Number n)
            return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Double asDouble(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}