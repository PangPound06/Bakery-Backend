package com.app.my_project.service;

import com.app.my_project.common.PaymentMethods;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.OrderRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * PaymentReconciler — เทียบสถานะการจ่ายเงินใน DB กับความจริงที่ Stripe
 *
 * ═══ ทำไมต้องมี ═══
 * webhook เป็นทางหลักที่เปลี่ยน order เป็น paid แต่ webhook มาช้าหรือไม่มาได้
 * (เช่น รันบน localhost โดยไม่ได้เปิด stripe listen หรือ server ล่มชั่วคราว)
 * ถ้าเชื่อ DB อย่างเดียว order ที่ลูกค้าจ่ายแล้วจะค้างเป็น awaiting_payment
 * แล้วถูกนำไปเขียนทับตอนลูกค้าสั่งรอบถัดไป — เงินเข้าแต่ order หาย
 *
 * คลาสนี้ถาม Stripe ตรงๆ ด้วย PaymentIntent id ที่ server เก็บไว้เอง
 * จึงยังเชื่อถือได้เท่ากับ webhook (ไม่มีข้อมูลใดมาจาก browser)
 */
@Service
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    public enum Outcome {
        /** Stripe ยืนยันว่าได้เงินแล้ว — order ถูกเปลี่ยนเป็น paid เรียบร้อย */
        PAID,
        /** กำลังตัดเงิน หรือถาม Stripe ไม่สำเร็จ — ห้ามแตะ order นี้ */
        IN_FLIGHT,
        /** ยังไม่ได้จ่าย — ยกเลิก QR/ฟอร์มบัตรเดิมแล้ว นำแถวไปใช้ต่อได้ */
        UNPAID
    }

    private final StripeService stripeService;
    private final PaymentStateService paymentStateService;
    private final OrderRepository orderRepository;

    public PaymentReconciler(StripeService stripeService,
            PaymentStateService paymentStateService,
            OrderRepository orderRepository) {
        this.stripeService = stripeService;
        this.paymentStateService = paymentStateService;
        this.orderRepository = orderRepository;
    }

    /**
     * ตรวจ order ที่ยังรอจ่ายกับ Stripe แล้วอัปเดต DB ให้ตรง (ไม่ยกเลิกอะไร)
     * ใช้กับหน้าที่ poll สถานะ — ลูกค้าเห็น "ชำระแล้ว" ทันทีแม้ webhook ยังไม่มา
     */
    public Outcome refresh(OrderEntity order) {
        if (!canAskStripe(order))
            return outcomeOf(order);
        try {
            return apply(order, stripeService.retrieveIntent(order.getStripePaymentIntentId()));
        } catch (StripeException e) {
            log.warn("ตรวจสถานะ {} กับ Stripe ไม่สำเร็จ: {}", order.getStripePaymentIntentId(), e.getMessage());
            return Outcome.IN_FLIGHT;
        }
    }

    /**
     * เตรียมก่อนลูกค้าเริ่มจ่ายเงินรอบใหม่: ปิดทุกรอบเก่าที่ค้างอยู่ให้เด็ดขาด
     *
     * รอบที่จ่ายแล้ว → บันทึกเป็น paid (ไม่ถูกเขียนทับอีก)
     * รอบที่ยังไม่จ่าย → ยกเลิก intent กับ Stripe ก่อน ลูกค้าจะได้ไม่มี QR เก่าที่ยังสแกนจ่ายได้
     *
     * @param orderType "online" หรือ "dine-in" — ปิดเฉพาะรอบของช่องทางเดียวกัน
     *                  QR ของบิลหน้าร้านจะไม่ถูกยกเลิกเพราะลูกค้ากดจ่ายออนไลน์ และกลับกัน
     * @return id ของ order ที่ห้ามนำไปเขียนทับ (จ่ายแล้วหรือยังไม่แน่ใจ)
     */
    public Set<Long> settleAwaiting(String email, String orderType) {
        Set<Long> locked = new HashSet<>();
        for (OrderEntity order : orderRepository.findByEmailAndPaymentStatus(email, PaymentStateService.AWAITING)) {
            if (orderType.equals(order.getOrderType()) && settle(order) != Outcome.UNPAID) {
                locked.add(order.getId());
            }
        }
        return locked;
    }

    /**
     * ปิดรอบจ่ายเงินของ order เดียว — ใช้ตอนลูกค้ากดยกเลิก QR / ฟอร์มบัตร
     *
     * UNPAID = ยกเลิก intent แล้ว ปล่อย order คืนได้อย่างปลอดภัย
     * PAID / IN_FLIGHT = ลูกค้าจ่ายไปแล้ว (หรือกำลังจ่าย) ห้ามปล่อย order
     */
    public Outcome settle(OrderEntity order) {
        if (!canAskStripe(order))
            return outcomeOf(order);
        String intentId = order.getStripePaymentIntentId();
        try {
            PaymentIntent intent = stripeService.retrieveIntent(intentId);
            Outcome current = apply(order, intent);
            // ถูกยกเลิกไปแล้ว (เช่น webhook canceled) — ไม่มีทางจ่ายได้อีก ไม่ต้องสั่งยกเลิกซ้ำ
            if (current != Outcome.UNPAID || "canceled".equals(intent.getStatus()))
                return current;
            if (stripeService.cancelIntent(intentId))
                return Outcome.UNPAID;
            // ยกเลิกไม่ได้ = ลูกค้าเพิ่งจ่ายพอดี ถามซ้ำอีกรอบ
            return apply(order, stripeService.retrieveIntent(intentId));
        } catch (StripeException e) {
            log.warn("ปิดรอบจ่ายเงิน {} ไม่สำเร็จ: {}", intentId, e.getMessage());
            return Outcome.IN_FLIGHT;
        }
    }

    /**
     * ปิดรอบจ่ายเงินกับ Stripe แล้วลบ order ทิ้งถ้าไม่ได้เงิน
     *
     * @return true = ลบแล้ว, false = ได้เงิน / กำลังตัดเงิน / สถานะเปลี่ยนไปแล้ว (order ยังอยู่)
     */
    public boolean discardIfUnpaid(OrderEntity order, String reason) {
        if (settle(order) != Outcome.UNPAID)
            return false;
        return paymentStateService.discardUnpaid(order.getId(), reason);
    }

    /**
     * Stripe แจ้งว่า intent จ่ายไม่สำเร็จหรือถูกยกเลิก (webhook)
     *
     * ═══ ไม่ลบทุกกรณี ═══
     * - intent ไม่ใช่ตัวปัจจุบันของ order: order ถูกนำไปใช้ต่อด้วย intent ใหม่แล้ว
     *   event ของ intent เก่าที่เรายกเลิกเองมาถึงทีหลัง ห้ามลบ order ที่ลูกค้ากำลังจ่ายอยู่
     * - บัตรถูกปฏิเสธ (payment_failed): ลูกค้ายังลองบัตรใบอื่นในฟอร์มเดิมได้
     *   order จะถูกลบเมื่อลูกค้ากดยกเลิกหรือเกินเวลา (UnpaidOrderSweeper)
     *
     * @param canceled true = payment_intent.canceled, false = payment_intent.payment_failed
     */
    public void onIntentEnded(Long orderId, String intentId, boolean canceled, String reason) {
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !intentId.equals(order.getStripePaymentIntentId())
                || !PaymentStateService.AWAITING.equals(order.getPaymentStatus())) {
            log.debug("ข้าม event ของ {} — ไม่ใช่รอบจ่ายเงินปัจจุบันของ order {}", intentId, orderId);
            return;
        }
        if (!canceled && PaymentMethods.CARD.equals(order.getPaymentMethod())) {
            log.info("บัตรของ order {} ถูกปฏิเสธ ({}) — รอลูกค้าลองใหม่", orderId, reason);
            return;
        }
        discardIfUnpaid(order, reason);
    }

    private Outcome apply(OrderEntity order, PaymentIntent intent) {
        return switch (intent.getStatus()) {
            case "succeeded" -> {
                paymentStateService.markPaid(order.getId(), intent.getId());
                yield Outcome.PAID;
            }
            case "processing" -> Outcome.IN_FLIGHT;
            default -> Outcome.UNPAID;
        };
    }

    /** ถาม Stripe ได้เมื่อ order ยังรอจ่าย มี intent ที่ server สร้างไว้ และตั้งค่า Stripe แล้ว */
    private boolean canAskStripe(OrderEntity order) {
        String intentId = order.getStripePaymentIntentId();
        return stripeService.isEnabled()
                && PaymentStateService.AWAITING.equals(order.getPaymentStatus())
                && intentId != null && !intentId.isBlank();
    }

    private static Outcome outcomeOf(OrderEntity order) {
        return PaymentStateService.PAID.equals(order.getPaymentStatus()) ? Outcome.PAID : Outcome.UNPAID;
    }
}
