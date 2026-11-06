package com.app.my_project.service;

import com.app.my_project.common.CartMode;
import com.app.my_project.common.PaymentMethods;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PaymentStateService — เปลี่ยนสถานะการจ่ายเงินของ order
 *
 * ═══ กฎเหล็ก ═══
 * paymentStatus = "paid" ตั้งได้สองทางเท่านั้น
 *  - Stripe ยืนยัน (webhook หรือ PaymentReconciler ถาม Stripe เอง)
 *  - แอดมินกดยืนยันยอดโอนพร้อมเพย์ที่เห็นในแอปธนาคารของร้าน (confirmTransfer)
 * การที่ frontend เห็นว่า confirmPayment() สำเร็จ หรือลูกค้ากด "แจ้งโอนแล้ว" *ห้าม* ใช้ยืนยันการจ่ายเงิน
 * เพราะทั้งสองอย่างมาจากฝั่งลูกค้า
 *
 * ═══ สถานะที่ใช้ ═══
 * Stripe: awaiting_payment → paid
 * โอนพร้อมเพย์: awaiting_payment → awaiting_verification (ลูกค้าแจ้งโอน) → paid | failed (แอดมินไม่พบยอด)
 *
 * ═══ รอบจ่ายเงินที่ไม่ได้เงิน = ลบทิ้ง ═══
 * ลูกค้ากดยกเลิก / QR หมดอายุ / ไม่แจ้งโอนในเวลา / เริ่มจ่ายไม่สำเร็จ → คืน stock แล้วลบ order ทิ้ง (discardUnpaid)
 * ไม่เก็บแถว expired ไว้ เพราะไม่มีเงินไหลผ่าน — สั่งแล้วยกเลิกวันละครั้งก็ได้แถวขยะวันละแถว
 * ที่เก็บไว้มีแค่ order ที่จ่ายแล้ว รอตรวจยอดโอน และที่แอดมินปฏิเสธสลิป (เก็บไว้เป็นหลักฐานสลิป)
 *
 * failed / expired ยังเป็นค่าที่ถูกต้อง เพราะมีข้อมูลเก่าในฐานข้อมูลและสลิปที่ถูกปฏิเสธ
 */
@Service
public class PaymentStateService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateService.class);

    public static final String AWAITING = "awaiting_payment";
    public static final String PAID = "paid";
    public static final String FAILED = "failed";
    public static final String EXPIRED = "expired";
    /** ลูกค้าแจ้งว่าโอนพร้อมเพย์แล้ว รอแอดมินตรวจยอดในแอปธนาคาร */
    public static final String VERIFYING = "awaiting_verification";

    /**
     * อายุของรอบจ่ายเงินที่ยังไม่ได้เงิน — เกินนี้ UnpaidOrderSweeper คืน stock แล้วลบทิ้ง
     *
     * ภายในเวลานี้ ลูกค้าที่กดจ่ายใหม่ (สลับวิธีจ่าย / กดย้อนกลับ) จะได้แถวเดิมกลับมาใช้ต่อ
     * เกินจากนี้ถือว่าคนละรอบการซื้อ
     */
    public static final Duration UNPAID_TTL = Duration.ofMinutes(30);

    /** เวลาที่ลูกค้ามีให้โอนพร้อมเพย์แล้วกดแจ้งโอน (หน้าเว็บนับถอยหลังตามนี้) */
    public static final Duration TRANSFER_PAY_WINDOW = UNPAID_TTL;

    /** วิธีจ่ายที่ลูกค้าจ่ายเองออนไลน์ — order ที่ค้างรอจ่ายนานเกินจะถูกเก็บกวาด (ไม่รวมเงินสด POS) */
    private static final List<String> SELF_SERVE_METHODS = List.of(
            PaymentMethods.QR_PROMPTPAY, PaymentMethods.CARD, PaymentMethods.PROMPTPAY_TRANSFER);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockService stockService;
    private final CartService cartService;
    private final JdbcTemplate jdbcTemplate;

    public PaymentStateService(OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            StockService stockService,
            CartService cartService,
            JdbcTemplate jdbcTemplate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockService = stockService;
        this.cartService = cartService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ═════════════════════════════════════════════════════════════════
    // IDEMPOTENCY
    // ═════════════════════════════════════════════════════════════════

    /**
     * บันทึก event id ลง tb_stripe_events
     *
     * Stripe รับประกันแค่ "at least once" — event เดิมอาจมาซ้ำได้
     * ถ้าไม่กัน จะคืน stock ซ้ำ/ส่งอีเมลซ้ำ
     *
     * @return true = event ใหม่ ให้ประมวลผลต่อ, false = เคยเห็นแล้ว ข้ามไป
     */
    @Transactional
    public boolean claimEvent(String eventId, String eventType) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO tb_stripe_events (event_id, event_type) VALUES (?, ?) " +
                        "ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType);
        if (inserted == 0) {
            log.info("ข้าม event ซ้ำ {} ({})", eventId, eventType);
        }
        return inserted > 0;
    }

    // ═════════════════════════════════════════════════════════════════
    // STATE TRANSITIONS
    // ═════════════════════════════════════════════════════════════════

    /**
     * จ่ายเงินสำเร็จ → paid + เคลียร์ตะกร้าของลูกค้า
     *
     * stock ถูกตัดไปแล้วตั้งแต่ตอนสร้าง order (จอง) จึงไม่ต้องตัดซ้ำที่นี่
     */
    @Transactional
    public void markPaid(Long orderId, String paymentIntentId) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            // ไม่ควรเกิด — order ถูกลบหลังยกเลิก intent กับ Stripe สำเร็จเท่านั้น
            log.error("⚠️ ได้เงินจาก {} แต่ไม่พบ order {} — ตรวจสอบใน Stripe Dashboard แล้วคืนเงินหรือออกบิลด้วยมือ",
                    paymentIntentId, orderId);
            return;
        }

        OrderEntity order = opt.get();
        if (PAID.equals(order.getPaymentStatus())) {
            log.info("order {} เป็น paid อยู่แล้ว ข้าม", orderId);
            return;
        }

        order.setPaymentId(paymentIntentId);
        order.setStripePaymentIntentId(paymentIntentId);
        finalizePaid(order);
    }

    // ═════════════════════════════════════════════════════════════════
    // PROMPTPAY TRANSFER — โอนเข้าบัญชีร้านตรง แอดมินเป็นคนยืนยัน
    // ═════════════════════════════════════════════════════════════════

    public enum TransferResult {
        OK,
        /** ไม่มี order นี้ หรือไม่ใช่ของลูกค้าคนนี้ */
        NOT_FOUND,
        /** order นี้ไม่ได้จ่ายด้วยการโอนพร้อมเพย์ */
        NOT_TRANSFER,
        /** สถานะไม่อยู่ในขั้นที่ทำรายการนี้ได้ (เช่น หมดเวลา/ยืนยันไปแล้ว) */
        WRONG_STATE,
        /** สลิปใบนี้ (QR เดียวกัน) ถูกแนบกับคำสั่งซื้ออื่นไปแล้ว */
        DUPLICATE_SLIP
    }

    /**
     * ตรวจก่อนเก็บรูปสลิป — order ของลูกค้าคนนี้ ยังรอโอน และสลิปไม่เคยถูกใช้กับ order อื่น
     *
     * เรียกก่อนอัปโหลดรูปขึ้น Cloudinary จะได้ไม่เก็บรูปของคำขอที่ยังไงก็ไม่ผ่าน
     * submitTransfer ตรวจซ้ำอีกรอบเสมอ (สถานะอาจเปลี่ยนระหว่างอัปโหลด)
     */
    public TransferResult checkSubmittable(Long orderId, String email, String slipReference) {
        return validateSubmission(orderId, email, slipReference);
    }

    /**
     * ลูกค้ากด "แจ้งโอนแล้ว" พร้อมสลิป → รอแอดมินตรวจยอด
     *
     * ยังไม่ใช่ paid — แค่หยุดนับเวลาหมดอายุ และล้างตะกร้าเพราะลูกค้าถือว่าสั่งซื้อเสร็จแล้ว
     * (ใช้ได้ทั้งออนไลน์และบิลหน้าร้าน)
     * ส่งสลิปใหม่ได้ (เช่น แนบผิดรูป) ตราบใดที่แอดมินยังไม่ตัดสิน
     *
     * @param slipUrl       รูปสลิปที่ตรวจ QR แล้วและเก็บบน Cloudinary
     * @param slipReference ค่าแทนสลิปจาก SlipQrReader — เก็บใน payment_id ไว้กันสลิปซ้ำ
     */
    @Transactional
    public TransferResult submitTransfer(Long orderId, String email, String slipUrl, String slipReference) {
        TransferResult check = validateSubmission(orderId, email, slipReference);
        if (check != TransferResult.OK)
            return check;

        // อยู่ใน transaction เดียวกัน — Hibernate คืน entity เดิมจาก cache ไม่ query ซ้ำ
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        order.setPaymentStatus(VERIFYING);
        order.setSlipImage(slipUrl);
        order.setPaymentId(slipReference);
        orderRepository.save(order);
        cartService.clear(email, CartMode.forOrderType(order.getOrderType()));

        log.info("🧾 order {} ({}) ลูกค้าแจ้งโอนพร้อมสลิปแล้ว รอตรวจสอบ", orderId, order.getOrdCode());
        return TransferResult.OK;
    }

    private TransferResult validateSubmission(Long orderId, String email, String slipReference) {
        Optional<OrderEntity> owned = orderRepository.findById(orderId).filter(o -> o.getEmail().equals(email));
        if (owned.isEmpty())
            return TransferResult.NOT_FOUND;
        OrderEntity order = owned.get();
        TransferResult check = checkDecidable(order);
        if (check != TransferResult.OK)
            return check;
        if (orderRepository.existsByPaymentIdAndIdNot(slipReference, order.getId()))
            return TransferResult.DUPLICATE_SLIP;
        return TransferResult.OK;
    }

    /**
     * แอดมินเห็นยอดเข้าบัญชีแล้ว → paid
     *
     * รับทั้ง awaiting_payment ด้วย เผื่อลูกค้าโอนแล้วลืมกดแจ้ง
     * แต่ไม่รับ expired — stock ถูกคืนไปแล้ว ต้องให้ลูกค้าสั่งใหม่หรือคืนเงิน
     */
    @Transactional
    public TransferResult confirmTransfer(Long orderId, String adminEmail) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty())
            return TransferResult.NOT_FOUND;
        OrderEntity order = opt.get();
        TransferResult check = checkDecidable(order);
        if (check != TransferResult.OK)
            return check;

        order.setNote(appendNote(order.getNote(), "ยืนยันยอดโอนพร้อมเพย์โดย " + adminEmail));
        finalizePaid(order);
        return TransferResult.OK;
    }

    /**
     * แอดมินไม่พบยอดโอน → คืน stock แล้วปิดเป็น failed
     *
     * order นี้ไม่ถูกลบเหมือนรอบที่ยกเลิกเอง — ลูกค้าแนบสลิปมาแล้ว ต้องเก็บไว้เป็นหลักฐานถ้ามีข้อโต้แย้ง
     */
    @Transactional
    public TransferResult rejectTransfer(Long orderId, String reason, String adminEmail) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty())
            return TransferResult.NOT_FOUND;
        OrderEntity order = opt.get();
        TransferResult check = checkDecidable(order);
        if (check != TransferResult.OK)
            return check;

        String detail = (reason == null || reason.isBlank()) ? "ไม่พบยอดโอน" : reason.trim();
        returnReserved(order);
        order.setPaymentStatus(FAILED);
        order.setOrderStatus("cancelled");
        order.setNote(appendNote(order.getNote(), detail + " (โดย " + adminEmail + ")"));
        orderRepository.save(order);

        log.info("↩️ order {} ({}) แอดมินปฏิเสธยอดโอน: {}", orderId, order.getOrdCode(), detail);
        return TransferResult.OK;
    }

    /**
     * order ที่ลูกค้าเริ่มจ่ายเองแต่ค้างรอจ่ายนานเกิน UNPAID_TTL — ให้ UnpaidOrderSweeper เก็บกวาด
     *
     * ไม่รวม awaiting_verification — order ที่แจ้งโอนแล้วต้องรอแอดมินตัดสินเสมอ
     * ไม่รวมเงินสด (POS) — แอดมินเป็นคนจัดการเอง
     */
    public List<OrderEntity> findAbandoned(LocalDateTime createdBefore) {
        return orderRepository.findByPaymentStatusAndPaymentMethodInAndCreatedAtBefore(
                AWAITING, SELF_SERVE_METHODS, createdBefore);
    }

    /** order โอนพร้อมเพย์ที่ยังไม่มีใครตัดสิน (ยังไม่ paid / ไม่ถูกปิด) */
    private static TransferResult checkDecidable(OrderEntity order) {
        if (!PaymentMethods.isPromptPayTransfer(order.getPaymentMethod()))
            return TransferResult.NOT_TRANSFER;
        if (!AWAITING.equals(order.getPaymentStatus()) && !VERIFYING.equals(order.getPaymentStatus()))
            return TransferResult.WRONG_STATE;
        return TransferResult.OK;
    }

    /** ขั้นสุดท้ายของทุกทางที่ได้เงิน — ตั้ง paid, ล้างตะกร้า, ปิดออเดอร์โต๊ะที่ผูกกับบิลนี้ */
    private void finalizePaid(OrderEntity order) {
        order.setPaymentStatus(PAID);
        orderRepository.save(order);

        cartService.clear(order.getEmail(), CartMode.forOrderType(order.getOrderType()));
        closeDineInOrders(order.getId());
        log.info("✅ order {} ({}) จ่ายเงินสำเร็จ", order.getId(), order.getOrdCode());
    }

    /**
     * รอบจ่ายเงินที่ไม่ได้เงิน → คืน stock ที่จองไว้ แล้วลบ order กับรายการทิ้ง
     *
     * ⚠️ ผู้เรียกต้องปิด intent กับ Stripe ก่อนเสมอ (PaymentReconciler.settle ได้ UNPAID)
     * ไม่งั้นลูกค้าอาจจ่ายเข้ามาหลังลบ แล้วเงินเข้าโดยไม่มี order
     *
     * ไม่ลบ order ที่จ่ายแล้วหรือแจ้งโอนแล้ว — ปลอดภัยแม้ถูกเรียกซ้ำหรือสลับลำดับกับ webhook
     *
     * @return true = ลบแล้ว, false = ไม่มี order นี้ หรือสถานะไม่ใช่รอบที่ทิ้งได้
     */
    @Transactional
    public boolean discardUnpaid(Long orderId, String reason) {
        return orderRepository.findById(orderId)
                .map(order -> discard(order, reason))
                .orElse(false);
    }

    private boolean discard(OrderEntity order, String reason) {
        String status = order.getPaymentStatus();
        boolean discardable = AWAITING.equals(status)
                // แถว expired/failed สมัยก่อนที่ยังไม่ถูกล้าง — stock คืนไปแล้ว ลบอย่างเดียว
                || EXPIRED.equals(status)
                || (FAILED.equals(status) && order.getSlipImage() == null);
        if (!discardable) {
            log.warn("ไม่ลบ order {} สถานะ {} (reason={})", order.getId(), status, reason);
            return false;
        }

        if (AWAITING.equals(status)) {
            returnReserved(order);
        } else if (isDineInBill(order)) {
            unlinkDineInOrders(order.getId());
        }
        orderItemRepository.deleteByOrderId(order.getId());
        orderRepository.delete(order);

        log.info("🗑️ ลบรอบจ่ายเงินที่ไม่ได้เงิน order {} ({}) ของ {}: {}",
                order.getId(), order.getOrdCode(), order.getEmail(), reason);
        return true;
    }

    /**
     * เตรียม order สำหรับการกดจ่ายเงินรอบใหม่ของลูกค้าคนเดิม (ออนไลน์)
     *
     * ═══ กติกาเดียวของเมธอดนี้ ═══
     * ลูกค้าหนึ่งคนมีแถวที่รอจ่ายได้ "แถวเดียว" — กดจ่ายใหม่ (สลับวิธีจ่าย / กดย้อนกลับมา)
     * ให้เขียนทับแถวเดิม ไม่สร้างแถวใหม่ เลข order จึงคงเดิมจนกว่าจะจ่ายสำเร็จ
     *
     * ═══ ทำอะไรบ้าง ═══
     * 1. หาแถว awaiting_payment ออนไลน์ของ email นี้ที่ไม่ถูกล็อก
     * 2. แถวที่เก่าเกิน UNPAID_TTL และแถวเกินมา → ลบทิ้ง (คืน stock)
     * 3. แถวล่าสุดที่ยังไม่หมดอายุ → คืน stock เดิม ลบ item เดิม เขียน item ใหม่ตามตะกร้า จอง stock ใหม่
     *
     * ═══ ห้ามเขียนทับ order ที่อาจจ่ายแล้ว ═══
     * ผู้เรียกต้องผ่าน PaymentReconciler.settleAwaiting() ก่อนเสมอ
     * — intent ของทุกแถวที่ไม่ถูกล็อกถูกยกเลิกกับ Stripe แล้ว ลบหรือเขียนทับได้อย่างปลอดภัย
     *
     * @param locked id ของ order ที่ห้ามแตะ (จ่ายแล้ว / กำลังตัดเงิน / ถาม Stripe ไม่สำเร็จ)
     * @return แถวที่เขียนทับแล้ว หรือ null = ไม่มีแถวค้าง ให้ไปสร้างใหม่
     */
    @Transactional
    public OrderEntity prepareForNewIntent(String email, Set<Long> locked, List<CartLine> wanted,
            double subtotal, double shipping, double total) {

        LocalDateTime cutoff = LocalDateTime.now().minus(UNPAID_TTL);
        List<OrderEntity> open = orderRepository.findByEmailAndPaymentStatus(email, AWAITING).stream()
                .filter(o -> "online".equals(o.getOrderType()) && !locked.contains(o.getId()))
                .sorted(Comparator.comparing(OrderEntity::getId).reversed())
                .toList();

        OrderEntity keep = open.stream()
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isAfter(cutoff))
                .findFirst()
                .orElse(null);
        for (OrderEntity other : open) {
            if (other != keep)
                discard(other, "ลูกค้าเริ่มการชำระเงินรอบใหม่");
        }
        if (keep == null)
            return null;

        // ── เขียนรายการใหม่ทับของเดิม ──
        returnReserved(keep);
        orderItemRepository.deleteByOrderId(keep.getId());

        for (CartLine line : wanted) {
            OrderItemEntity item = new OrderItemEntity();
            item.setOrderId(keep.getId());
            item.setProductId(line.productId());
            item.setProductName(line.productName());
            item.setPrice(line.unitPrice());
            item.setQuantity(line.rawQuantity());
            item.setSelectedOption(line.selectedOption());
            item.setImage(line.image());
            item.setCancelledQty(0);
            orderItemRepository.save(item);

            stockService.decreaseStock(line.productId(), line.rawQuantity());
        }

        keep.setSubtotal(subtotal);
        keep.setShipping(shipping);
        keep.setTotal(total);
        keep.setPaymentStatus(AWAITING);
        keep.setOrderStatus("pending");
        keep.setStripePaymentIntentId(null);
        keep.setPaymentId(null);
        keep.setSlipImage(null);
        keep.setNote(null);
        keep.setCreatedAt(LocalDateTime.now());
        orderRepository.save(keep);

        log.info("ใช้ order {} ({}) ต่อสำหรับการจ่ายเงินรอบใหม่ของ {}",
                keep.getId(), keep.getOrdCode(), email);
        return keep;
    }

    /** หนึ่งบรรทัดในตะกร้าที่คิดราคาแล้ว — quantity เป็น "ชิ้น" เหมือนใน tb_order_items */
    public record CartLine(Long productId, String productName, String selectedOption,
            int rawQuantity, double unitPrice, String image) {}

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /**
     * บิลหน้าร้านจ่ายแล้ว → ปิดออเดอร์ของโต๊ะที่ถูกรวมเข้ามาด้วย
     *
     * ออเดอร์ออนไลน์จะไม่มีแถวไหนตรง คำสั่งนี้จึงไม่ทำอะไรเลย ปล่อยให้วิ่งได้ทุกครั้ง
     */
    private void closeDineInOrders(Long billOrderId) {
        try {
            int n = jdbcTemplate.update(
                    "UPDATE tb_dinein_orders SET payment_status = 'paid' WHERE bill_order_id = ?",
                    billOrderId);
            if (n > 0) {
                log.info("ปิดออเดอร์หน้าร้าน {} รายการของบิล {}", n, billOrderId);
            }
        } catch (Exception e) {
            // พลาดตรงนี้ไม่ควรทำให้ webhook fail แล้ว Stripe ยิงซ้ำ
            log.error("ปิดออเดอร์หน้าร้านของบิล {} ไม่สำเร็จ", billOrderId, e);
        }
    }

    /**
     * บิลหน้าร้านไม่ได้เงิน → ปลดออเดอร์โต๊ะออกจากบิล ให้กดเรียกเก็บใหม่ได้
     *
     * ล้างทั้งสถานะและ bill_order_id — บิลถูกลบไปแล้ว ไม่มีแถวให้ชี้
     */
    private void unlinkDineInOrders(Long billOrderId) {
        jdbcTemplate.update(
                "UPDATE tb_dinein_orders SET payment_status = NULL, bill_order_id = NULL " +
                        "WHERE bill_order_id = ? AND payment_status IS DISTINCT FROM 'paid'",
                billOrderId);
    }

    /**
     * คืนของที่ order นี้จองไว้
     *
     * ออนไลน์: คืน stock ตามรายการ
     * บิลหน้าร้าน: ไม่คืน stock — ของถูกตัดตอนสั่งที่โต๊ะและกินไปแล้ว แค่ปลดออเดอร์โต๊ะออกจากบิล
     */
    private void returnReserved(OrderEntity order) {
        if (isDineInBill(order)) {
            unlinkDineInOrders(order.getId());
            return;
        }
        List<StockService.StockChange> changes = orderItemRepository.findByOrderId(order.getId()).stream()
                .map(i -> new StockService.StockChange(i.getProductId(), i.getQuantity()))
                .toList();
        if (!changes.isEmpty())
            stockService.batchIncreaseStock(changes);
    }

    private static boolean isDineInBill(OrderEntity order) {
        return "dine-in".equals(order.getOrderType());
    }

    private static String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank())
            return existing;
        if (existing == null || existing.isBlank())
            return "[ระบบ] " + addition;
        return existing + "\n[ระบบ] " + addition;
    }
}