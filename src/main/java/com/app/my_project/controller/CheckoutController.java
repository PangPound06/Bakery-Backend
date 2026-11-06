package com.app.my_project.controller;

import com.app.my_project.common.CartMode;
import com.app.my_project.common.CheckoutMethod;
import com.app.my_project.common.CurrentUser;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CheckoutController — จุดเริ่มต้นของการชำระเงินจริง
 *
 * ═══ ทำไมไม่ใช้ POST /api/orders เดิม ═══
 * endpoint เดิมรับ email, ราคา และ paymentStatus จาก request body ทั้งหมด
 * ซึ่งจำเป็นสำหรับหน้า POS ของแอดมิน แต่ใช้กับลูกค้าจริงไม่ได้
 * endpoint นี้เลยไม่รับตัวเลขใดๆ จาก client เลย นอกจากที่อยู่จัดส่ง
 *
 * cancel / status / transfer submit ใช้ได้กับบิลหน้าร้านด้วย (order ของลูกค้าคนนี้ทุกชนิด)
 * หน้าเว็บทั้งสองฝั่งจึงใช้ panel จ่ายเงินชุดเดียวกัน
 *
 * ═══ ลำดับการทำงานของ POST /intent ═══
 * 1. ยืนยันตัวตนจาก JWT → ได้ email (ไม่เชื่อ email ใน body)
 * 2. เช็คว่าร้านเปิดรับออเดอร์อยู่
 * 3. ปิดรอบจ่ายเงินเก่าที่ค้างอยู่ — จ่ายแล้วบันทึกเป็น paid, ยังไม่จ่ายยกเลิก QR เดิม (PaymentReconciler)
 * 4. คำนวณราคาใหม่ทั้งใบจาก tb_products
 * 5. สร้าง order เป็น awaiting_payment + จอง stock
 * 6. ออกช่องทางจ่ายเงินตามที่เลือก (PaymentStarter)
 *
 * ตะกร้ายังไม่ถูกล้างตรงนี้ — รอได้เงิน (Stripe) หรือรอลูกค้าแจ้งโอน (transfer) ก่อน
 * ยกเลิก / ไม่จ่ายในเวลา → order ถูกลบทิ้ง ไม่เหลือแถวในฐานข้อมูล
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    /** ข้อมูลจัดส่ง — เป็นข้อมูลเดียวที่รับจาก client */
    private record Delivery(String name, String phone, String address, String note) {
        boolean isComplete() {
            return !isBlank(name) && !isBlank(phone) && !isBlank(address);
        }
    }

    private final CurrentUser currentUser;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PricingService pricingService;
    private final PaymentStateService paymentStateService;
    private final StripeService stripeService;
    private final StoreSettingService storeSettingService;
    private final PaymentReconciler paymentReconciler;
    private final PaymentStarter paymentStarter;
    private final TransferSlipService transferSlipService;

    public CheckoutController(CurrentUser currentUser,
            OrderRepository orderRepository,
            OrderService orderService,
            PricingService pricingService,
            PaymentStateService paymentStateService,
            StripeService stripeService,
            StoreSettingService storeSettingService,
            PaymentReconciler paymentReconciler,
            PaymentStarter paymentStarter,
            TransferSlipService transferSlipService) {
        this.currentUser = currentUser;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.pricingService = pricingService;
        this.paymentStateService = paymentStateService;
        this.stripeService = stripeService;
        this.storeSettingService = storeSettingService;
        this.paymentReconciler = paymentReconciler;
        this.paymentStarter = paymentStarter;
        this.transferSlipService = transferSlipService;
    }

    // ═════════════════════════════════════════════════════════════════
    // PREVIEW — ให้หน้า checkout แสดงยอดที่ server คำนวณ (ไม่ใช่ที่ browser คิดเอง)
    // ═════════════════════════════════════════════════════════════════

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        try {
            PricingService.PricedCart cart = pricingService.priceCart(email, CartMode.ONLINE);
            return ResponseEntity.ok(Map.of("success", true, "summary", toSummaryMap(cart)));
        } catch (PricingService.CartValidationException e) {
            return badRequest(e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // START PAYMENT
    // ═════════════════════════════════════════════════════════════════

    @PostMapping("/intent")
    public ResponseEntity<?> createIntent(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        Delivery delivery = new Delivery(str(body.get("receiverName")), str(body.get("receiverPhone")),
                str(body.get("receiverAddress")), str(body.get("note")));
        if (!delivery.isComplete())
            return badRequest("กรุณากรอกข้อมูลการจัดส่งให้ครบถ้วน");

        Optional<CheckoutMethod> parsed = CheckoutMethod.parse(str(body.get("paymentMethod")));
        if (parsed.isEmpty())
            return badRequest("กรุณาเลือกวิธีชำระเงิน");
        CheckoutMethod method = parsed.get();

        if (method.viaStripe() && !stripeService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("success", false, "error", "ระบบชำระเงินยังไม่พร้อมใช้งาน"));
        }
        if (!storeSettingService.isOnlineOrderingOpen())
            return storeClosed();

        OrderEntity order = null;
        try {
            // ─── คำนวณราคาใหม่ทั้งใบ ← หัวใจของความปลอดภัย ───
            PricingService.PricedCart cart = pricingService.priceCart(email, CartMode.ONLINE);
            order = reserveOrder(email, cart, method, delivery);

            Map<String, Object> resp = new HashMap<>(paymentStarter.start(order, cart.total(), email, method));
            resp.put("success", true);
            resp.put("method", method.requestValue());
            resp.put("orderId", order.getId());
            resp.put("ordCode", order.getOrdCode());
            resp.put("total", cart.total());
            return ResponseEntity.ok(resp);

        } catch (PricingService.CartValidationException | PricingService.ProductNotFoundException e) {
            return badRequest(e.getMessage());

        } catch (Exception e) {
            log.error("เริ่มการชำระเงินไม่สำเร็จ (email={}, method={})", email, method, e);
            // ถ้า order ถูกสร้างไปแล้วแต่ขั้นถัดไปล้ม → คืน stock และลบทิ้งทันที ไม่ให้ค้าง
            if (order != null) {
                paymentStateService.discardUnpaid(order.getId(), "เริ่มการชำระเงินไม่สำเร็จ");
            }
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "ไม่สามารถเริ่มการชำระเงินได้ กรุณาลองใหม่"));
        }
    }

    /**
     * ได้ order สถานะ awaiting_payment ที่จอง stock ตามตะกร้าแล้ว
     *
     * ลูกค้ามีแถวที่ยังไม่จ่ายค้างอยู่ → เขียนทับแถวนั้น ไม่สร้างแถวใหม่ (ดู prepareForNewIntent)
     * แต่ปิดรอบเก่าก่อนเสมอ — order ที่จ่ายแล้วแต่ webhook ยังไม่มา จะไม่ถูกเขียนทับ
     */
    private OrderEntity reserveOrder(String email, PricingService.PricedCart cart,
            CheckoutMethod method, Delivery delivery) {

        // intent ของทุกรอบที่ยังไม่จ่ายถูกยกเลิกกับ Stripe ตรงนี้ ที่เหลือเป็นรอบที่ห้ามแตะ
        Set<Long> locked = paymentReconciler.settleAwaiting(email, "online");

        OrderEntity order = paymentStateService.prepareForNewIntent(
                email, locked, toCartLines(cart), cart.subtotal(), cart.shipping(), cart.total());
        if (order == null) {
            return orderService.createOrder(email, buildOrderRequest(cart, method, delivery));
        }

        // รายการกับ stock ถูกเขียนใหม่ใน prepareForNewIntent แล้ว
        order.setPaymentMethod(method.storedValue());
        order.setReceiverName(delivery.name());
        order.setReceiverPhone(delivery.phone());
        order.setReceiverAddress(delivery.address());
        order.setNote(delivery.note());
        return orderRepository.save(order);
    }

    // ═════════════════════════════════════════════════════════════════
    // TRANSFER SUBMITTED — ลูกค้ากด "แจ้งโอนแล้ว"
    // ═════════════════════════════════════════════════════════════════

    /** multipart: slip = รูปสลิป (JPG/PNG) — บังคับ และต้องมี QR ของธนาคาร */
    @PostMapping(value = "/transfer/{orderId}/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submitTransfer(@PathVariable Long orderId,
            @RequestParam(value = "slip", required = false) MultipartFile slip,
            @RequestHeader(value = "Authorization", required = false) String auth) throws IOException {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();
        if (slip == null || slip.isEmpty())
            return badRequest("กรุณาแนบรูปสลิปการโอนเงิน");

        return switch (transferSlipService.submit(orderId, email, slip.getBytes(), slip.getContentType())) {
            case OK -> ResponseEntity.ok(Map.of("success", true,
                    "paymentStatus", PaymentStateService.VERIFYING));
            case NOT_FOUND -> notFound();
            case NOT_TRANSFER -> badRequest("คำสั่งซื้อนี้ไม่ได้ชำระด้วยการโอนพร้อมเพย์");
            case WRONG_STATE -> ResponseEntity.status(409).body(Map.of("success", false,
                    "error", "คำสั่งซื้อนี้หมดเวลาหรือถูกตรวจสอบไปแล้ว กรุณาตรวจสอบที่หน้าคำสั่งซื้อของฉัน"));
            case DUPLICATE_SLIP -> ResponseEntity.status(409).body(Map.of("success", false,
                    "error", "สลิปนี้ถูกใช้กับคำสั่งซื้ออื่นแล้ว กรุณาแนบสลิปของรายการนี้"));
            case INVALID_FILE -> badRequest("รองรับเฉพาะรูป JPG หรือ PNG ขนาดไม่เกิน 5MB");
            case NO_QR -> badRequest("ไม่พบ QR Code ในรูป กรุณาแนบสลิปจากแอปธนาคารที่เห็น QR ชัดเจน");
            case PAYMENT_QR -> badRequest("รูปนี้เป็น QR สำหรับจ่ายเงิน ไม่ใช่สลิป กรุณาแนบสลิปหลังโอนแล้ว");
            case STORAGE_FAILED -> ResponseEntity.status(503).body(Map.of("success", false,
                    "error", "บันทึกสลิปไม่สำเร็จ กรุณาลองใหม่อีกครั้ง"));
        };
    }

    // ═════════════════════════════════════════════════════════════════
    // CANCEL — ลูกค้ากดปิดหน้า QR เอง
    // ═════════════════════════════════════════════════════════════════

    @PostMapping("/cancel/{orderId}")
    public ResponseEntity<?> cancel(@PathVariable Long orderId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        Optional<OrderEntity> owned = orderRepository.findById(orderId)
                .filter(o -> email.equals(o.getEmail()));
        if (owned.isEmpty())
            return notFound();
        OrderEntity order = owned.get();

        // แจ้งโอนแล้ว เงินอาจอยู่ในบัญชีร้าน — ต้องให้แอดมินตัดสิน ลูกค้ายกเลิกเองไม่ได้
        if (PaymentStateService.VERIFYING.equals(order.getPaymentStatus())) {
            return ResponseEntity.status(409).body(Map.of("success", false, "verifying", true,
                    "error", "แจ้งโอนแล้ว รอร้านตรวจสอบยอด ไม่สามารถยกเลิกเองได้"));
        }
        // ลูกค้าอาจสแกนจ่ายไปแล้วก่อนกดยกเลิก — ห้ามปล่อย order ที่ได้เงินแล้ว
        if (paymentReconciler.settle(order) != PaymentReconciler.Outcome.UNPAID) {
            return ResponseEntity.status(409).body(Map.of("success", false, "paid", true,
                    "error", "คำสั่งซื้อนี้ชำระเงินแล้ว ไม่สามารถยกเลิกการชำระเงินได้"));
        }
        paymentStateService.discardUnpaid(order.getId(), "ลูกค้ายกเลิกเอง");
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ═════════════════════════════════════════════════════════════════
    // STATUS — หน้า QR และหน้า success เรียก poll ตัวนี้จนกว่าจะได้ผล
    // ═════════════════════════════════════════════════════════════════

    @GetMapping("/status/{orderId}")
    public ResponseEntity<?> status(@PathVariable Long orderId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        return orderRepository.findById(orderId)
                .filter(o -> email.equals(o.getEmail())) // ห้ามดู order ของคนอื่น
                .map(this::refreshFromStripe)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "orderId", o.getId(),
                        "ordCode", o.getOrdCode() == null ? "" : o.getOrdCode(),
                        "paymentMethod", o.getPaymentMethod() == null ? "" : o.getPaymentMethod(),
                        "paymentStatus", o.getPaymentStatus() == null ? "" : o.getPaymentStatus(),
                        "orderStatus", o.getOrderStatus() == null ? "" : o.getOrderStatus(),
                        "total", o.getTotal() == null ? 0.0 : o.getTotal())))
                .orElseGet(this::notFound);
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /**
     * ถาม Stripe ซ้ำถ้ายังรอจ่ายอยู่ แล้วอ่าน order ใหม่จาก DB
     * หน้าเว็บจึงเห็น "ชำระแล้ว" ทันทีที่ Stripe ได้เงิน แม้ webhook ยังมาไม่ถึง
     */
    private OrderEntity refreshFromStripe(OrderEntity order) {
        if (paymentReconciler.refresh(order) != PaymentReconciler.Outcome.PAID)
            return order;
        return orderRepository.findById(order.getId()).orElse(order);
    }

    /** email จาก JWT ของลูกค้าเท่านั้น — ไม่รับจาก body */
    private String resolveEmail(String authHeader) {
        return currentUser.customerEmail(authHeader).orElse(null);
    }

    /** แปลงตะกร้าที่คิดราคาแล้วเป็นรูปแบบที่เอาไปเทียบกับ order ค้างได้ */
    private static List<PaymentStateService.CartLine> toCartLines(PricingService.PricedCart cart) {
        return cart.lines().stream()
                .map(l -> new PaymentStateService.CartLine(
                        l.productId(), l.productName(), l.selectedOption(),
                        l.rawQuantity(), l.unitPrice(), l.image()))
                .toList();
    }

    private static OrderService.CreateOrderRequest buildOrderRequest(
            PricingService.PricedCart cart, CheckoutMethod method, Delivery delivery) {

        List<OrderService.OrderItemRequest> items = cart.lines().stream()
                .map(line -> new OrderService.OrderItemRequest(
                        line.productId(),
                        line.productName(),
                        line.unitPrice(),
                        line.rawQuantity(), // เก็บเป็น "ชิ้น" ตาม convention เดิมของ tb_order_items
                        line.selectedOption(),
                        line.image()))
                .toList();

        return new OrderService.CreateOrderRequest(
                cart.subtotal(),
                cart.shipping(),
                cart.total(),
                method.storedValue(),
                PaymentStateService.AWAITING, // ← ไม่ใช่ "paid" จนกว่าจะยืนยันได้เงินจริง
                null, // paymentId มาทีหลังจาก Stripe
                "online",
                "pending",
                null, // สลิปมาทีหลังตอนลูกค้าแจ้งโอน
                null, // ไม่เก็บชื่อบนบัตร — Stripe จัดการเอง
                null, // ไม่เก็บ 4 ตัวท้าย — ดูได้ใน Stripe Dashboard
                delivery.name(), delivery.phone(), delivery.address(), delivery.note(),
                items);
    }

    private static Map<String, Object> toSummaryMap(PricingService.PricedCart cart) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PricingService.PricedLine l : cart.lines()) {
            Map<String, Object> m = new HashMap<>();
            m.put("productId", l.productId());
            m.put("productName", l.productName());
            m.put("image", l.image());
            m.put("selectedOption", l.selectedOption());
            m.put("unitPrice", l.unitPrice());
            m.put("quantity", l.displayQuantity());
            m.put("lineTotal", l.lineTotal());
            items.add(m);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("items", items);
        m.put("subtotal", cart.subtotal());
        m.put("shipping", cart.shipping());
        m.put("total", cart.total());
        return m;
    }

    private ResponseEntity<?> storeClosed() {
        StoreSettingEntity st = storeSettingService.getStatus();
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("storeClosed", true);
        resp.put("error", isBlank(st.getClosedMessage())
                ? "ขณะนี้ร้านปิดรับออเดอร์ออนไลน์ชั่วคราว"
                : st.getClosedMessage());
        resp.put("reopenAt", st.getReopenAt() == null ? "" : st.getReopenAt());
        return ResponseEntity.status(403).body(resp);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error", "กรุณาเข้าสู่ระบบ"));
    }

    private ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", "ไม่พบคำสั่งซื้อ"));
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", message));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
