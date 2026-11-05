package com.app.my_project.controller;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CheckoutController — จุดเริ่มต้นของการชำระเงินจริง
 *
 * ═══ ทำไมไม่ใช้ POST /api/orders เดิม ═══
 * endpoint เดิมรับ email, ราคา และ paymentStatus จาก request body ทั้งหมด
 * ซึ่งจำเป็นสำหรับหน้า POS ของแอดมิน แต่ใช้กับลูกค้าจริงไม่ได้
 * endpoint นี้เลยไม่รับตัวเลขใดๆ จาก client เลย นอกจากที่อยู่จัดส่ง
 *
 * ═══ ลำดับการทำงานของ POST /intent ═══
 * 1. ยืนยันตัวตนจาก JWT → ได้ email (ไม่เชื่อ email ใน body)
 * 2. เช็คว่าร้านเปิดรับออเดอร์อยู่
 * 3. ยกเลิก order ค้างรอจ่ายของ user คนนี้ (คืน stock ที่จองไว้)
 * 4. คำนวณราคาใหม่ทั้งใบจาก tb_products
 * 5. สร้าง order เป็น awaiting_payment + จอง stock
 * 6. สร้าง PaymentIntent แล้วส่ง QR (PromptPay) หรือ client_secret (บัตร) กลับไป
 *
 * ตะกร้ายังไม่ถูกล้างตรงนี้ — รอ webhook ยืนยันว่าได้เงินก่อน
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PricingService pricingService;
    private final PaymentStateService paymentStateService;
    private final StripeService stripeService;
    private final StoreSettingService storeSettingService;

    public CheckoutController(JwtService jwtService,
            UserRepository userRepository,
            OrderRepository orderRepository,
            OrderService orderService,
            PricingService pricingService,
            PaymentStateService paymentStateService,
            StripeService stripeService,
            StoreSettingService storeSettingService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.pricingService = pricingService;
        this.paymentStateService = paymentStateService;
        this.stripeService = stripeService;
        this.storeSettingService = storeSettingService;
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
            PricingService.PricedCart cart = pricingService.priceCart(email);
            return ResponseEntity.ok(Map.of("success", true, "summary", toSummaryMap(cart)));
        } catch (PricingService.CartValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // CREATE PAYMENT INTENT
    // ═════════════════════════════════════════════════════════════════

    @PostMapping("/intent")
    public ResponseEntity<?> createIntent(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        if (!stripeService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("success", false, "error", "ระบบชำระเงินยังไม่พร้อมใช้งาน"));
        }

        // ─── ข้อมูลจัดส่ง เป็นข้อมูลเดียวที่รับจาก client ───
        String receiverName = str(body.get("receiverName"));
        String receiverPhone = str(body.get("receiverPhone"));
        String receiverAddress = str(body.get("receiverAddress"));
        String note = str(body.get("note"));
        String method = normalizeMethod(str(body.get("paymentMethod")));

        if (isBlank(receiverName) || isBlank(receiverPhone) || isBlank(receiverAddress)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "กรุณากรอกข้อมูลการจัดส่งให้ครบถ้วน"));
        }
        if (method == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "กรุณาเลือกวิธีชำระเงิน"));
        }

        // ─── ร้านเปิดอยู่ไหม ───
        if (!storeSettingService.isOnlineOrderingOpen()) {
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

        OrderEntity order = null;
        try {
            // ─── คำนวณราคาใหม่ทั้งใบ ← หัวใจของความปลอดภัย ───
            PricingService.PricedCart cart = pricingService.priceCart(email);

            // ─── มีแถวที่ยังไม่จ่ายค้างอยู่ → เขียนทับแถวนั้น ไม่สร้างแถวใหม่ ───
            PaymentStateService.ReuseResult reuse = paymentStateService.prepareForNewIntent(
                    email, toCartLines(cart), cart.subtotal(), cart.shipping(), cart.total());
            reuse.intentsToCancel().forEach(stripeService::cancelIntent);

            if (reuse.reusable() != null) {
                // รายการกับ stock ถูกเขียนใหม่ใน prepareForNewIntent แล้ว
                order = reuse.reusable();
                order.setPaymentMethod(storedMethod(method));
                order.setReceiverName(receiverName);
                order.setReceiverPhone(receiverPhone);
                order.setReceiverAddress(receiverAddress);
                order.setNote(note);
                order = orderRepository.save(order);
            } else {
                // ─── สร้าง order + จอง stock ───
                order = orderService.createOrder(email, buildOrderRequest(
                        cart, method, receiverName, receiverPhone, receiverAddress, note));
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("method", method);
            resp.put("orderId", order.getId());
            resp.put("ordCode", order.getOrdCode());
            resp.put("total", cart.total());

            if (StripeService.METHOD_PROMPTPAY.equals(method)) {
                StripeService.PromptPayQr qr = stripeService.createPromptPayIntent(order, cart, email);
                order.setStripePaymentIntentId(qr.paymentIntentId());
                orderRepository.save(order);

                resp.put("qrImageUrl", qr.qrImageUrl());
                resp.put("qrData", qr.qrData());
                resp.put("hostedUrl", qr.hostedInstructionsUrl());
            } else {
                StripeService.CardIntent ci = stripeService.createCardIntent(order, cart, email);
                order.setStripePaymentIntentId(ci.paymentIntentId());
                orderRepository.save(order);

                resp.put("clientSecret", ci.clientSecret());
            }

            return ResponseEntity.ok(resp);

        } catch (PricingService.CartValidationException | PricingService.ProductNotFoundException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));

        } catch (Exception e) {
            log.error("สร้าง payment intent ไม่สำเร็จ (email={}, method={})", email, method, e);
            // ถ้า order ถูกสร้างไปแล้วแต่ Stripe ล้ม → คืน stock ทันที ไม่ให้ค้าง
            if (order != null) {
                paymentStateService.releaseOrder(order.getId(),
                        PaymentStateService.FAILED, "สร้าง PaymentIntent ไม่สำเร็จ");
            }
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "ไม่สามารถเริ่มการชำระเงินได้ กรุณาลองใหม่"));
        }
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

        return orderRepository.findById(orderId)
                .filter(o -> email.equals(o.getEmail()))
                .<ResponseEntity<?>>map(o -> {
                    stripeService.cancelIntent(o.getStripePaymentIntentId());
                    paymentStateService.releaseOrder(o.getId(),
                            PaymentStateService.EXPIRED, "ลูกค้ายกเลิกเอง");
                    return ResponseEntity.ok(Map.of("success", true));
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("success", false, "error", "ไม่พบคำสั่งซื้อ")));
    }

    // ═════════════════════════════════════════════════════════════════
    // STATUS — หน้า QR และหน้า success เรียก poll ตัวนี้จนกว่า webhook จะมาถึง
    // ═════════════════════════════════════════════════════════════════

    @GetMapping("/status/{orderId}")
    public ResponseEntity<?> status(@PathVariable Long orderId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        return orderRepository.findById(orderId)
                .filter(o -> email.equals(o.getEmail())) // ห้ามดู order ของคนอื่น
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "orderId", o.getId(),
                        "ordCode", o.getOrdCode() == null ? "" : o.getOrdCode(),
                        "paymentStatus", o.getPaymentStatus() == null ? "" : o.getPaymentStatus(),
                        "orderStatus", o.getOrderStatus() == null ? "" : o.getOrderStatus(),
                        "total", o.getTotal() == null ? 0.0 : o.getTotal())))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("success", false, "error", "ไม่พบคำสั่งซื้อ")));
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /** email จาก JWT เท่านั้น — ไม่รับจาก body */
    private String resolveEmail(String authHeader) {
        Long userId = jwtService.getUserIdFromHeader(authHeader);
        if (userId == null)
            return null;
        return userRepository.findById(userId).map(UserEntity::getEmail).orElse(null);
    }

    /** แปลงตะกร้าที่คิดราคาแล้วเป็นรูปแบบที่เอาไปเทียบกับ order ค้างได้ */
    private static List<PaymentStateService.CartLine> toCartLines(PricingService.PricedCart cart) {
        List<PaymentStateService.CartLine> lines = new ArrayList<>();
        for (PricingService.PricedLine l : cart.lines()) {
            lines.add(new PaymentStateService.CartLine(
                    l.productId(), l.productName(), l.selectedOption(),
                    l.rawQuantity(), l.unitPrice(), l.image()));
        }
        return lines;
    }

    /**
     * แปลงวิธีชำระเงินเป็นค่าที่เก็บใน tb_orders.payment_method
     *
     * ค่าที่คืนต้องตรงกับที่ frontend ใช้อยู่แล้ว: qr_promptpay / card / cash
     * แต่ละ intent สร้างด้วย payment method type เดียว ค่านี้จึงตรงกับความจริงเสมอ
     */
    private static String storedMethod(String method) {
        return StripeService.METHOD_PROMPTPAY.equals(method) ? "qr_promptpay" : "card";
    }

    /** รับแค่ 2 ค่าที่รู้จัก อย่างอื่นตีเป็น null */
    private static String normalizeMethod(String raw) {
        if (raw == null)
            return null;
        String v = raw.toLowerCase();
        if (StripeService.METHOD_PROMPTPAY.equals(v) || StripeService.METHOD_CARD.equals(v))
            return v;
        return null;
    }

    private OrderService.CreateOrderRequest buildOrderRequest(
            PricingService.PricedCart cart,
            String method,
            String name, String phone, String address, String note) {

        List<OrderService.OrderItemRequest> items = new ArrayList<>();
        for (PricingService.PricedLine line : cart.lines()) {
            items.add(new OrderService.OrderItemRequest(
                    line.productId(),
                    line.productName(),
                    line.unitPrice(),
                    line.rawQuantity(), // เก็บเป็น "ชิ้น" ตาม convention เดิมของ tb_order_items
                    line.selectedOption(),
                    line.image()));
        }

        return new OrderService.CreateOrderRequest(
                cart.subtotal(),
                cart.shipping(),
                cart.total(),
                storedMethod(method),
                PaymentStateService.AWAITING, // ← ไม่ใช่ "paid" จนกว่า webhook จะยืนยัน
                null, // paymentId มาทีหลังจาก Stripe
                "online",
                "pending",
                null, // ไม่มีสลิปแล้ว
                null, // ไม่เก็บชื่อบนบัตร — Stripe จัดการเอง
                null, // ไม่เก็บ 4 ตัวท้าย — ดูได้ใน Stripe Dashboard
                name, phone, address, note,
                items);
    }

    private Map<String, Object> toSummaryMap(PricingService.PricedCart cart) {
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

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error", "กรุณาเข้าสู่ระบบ"));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}