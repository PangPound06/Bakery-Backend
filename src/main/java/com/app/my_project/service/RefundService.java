package com.app.my_project.service;

import com.app.my_project.common.PaymentMethods;
import com.app.my_project.entity.OrderEntity;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RefundService — คำนวณและดำเนินการคืนเงิน
 *
 * ═══ กติกาที่ตกลงกันไว้ ═══
 * ค่าธรรมเนียม Stripe ไม่ได้คืนให้ร้านเวลา refund ร้านจึงผลักภาระให้ลูกค้า
 * ยกเว้นกรณีที่ร้านเป็นฝ่ายทำไม่ได้ (ของหมด) แอดมินเลือกคืนเต็มจำนวนได้
 *
 * ═══ สูตร ═══
 * ยกเลิกทั้งใบ:
 * คืน = ยอดที่เก็บ − ค่าธรรมเนียมทั้งหมด − ที่คืนไปแล้ว
 *
 * ยกเลิกบางรายการ:
 * สัดส่วน = ราคารายการที่ยกเลิก ÷ ยอดที่เก็บทั้งหมด
 * คืน = ราคารายการที่ยกเลิก − (ค่าธรรมเนียมทั้งหมด × สัดส่วน)
 * (ค่าส่งไม่คืน เพราะร้านจ่ายไปแล้ว)
 *
 * ═══ order ที่โอนพร้อมเพย์เข้าบัญชีร้านตรง ═══
 * ไม่มีรายการใน Stripe ให้คืน — ระบบคำนวณและบันทึกยอดเหมือนเดิม (ค่าธรรมเนียม = 0)
 * แต่แอดมินต้องโอนเงินคืนลูกค้าเองผ่านแอปธนาคาร (ดู isManualPayout)
 *
 * ═══ ทำไมทำงานด้วยหน่วย "สตางค์" ═══
 * double บวกลบกันแล้วเกิดเศษทศนิยมเพี้ยน (0.1 + 0.2 = 0.30000000000000004)
 * เรื่องเงินจึงคิดเป็นจำนวนเต็มทั้งหมด แล้วค่อยแปลงเป็นบาทตอนแสดงผล
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /** โหมดที่แอดมินเลือกได้ตอนอนุมัติ */
    public static final String MODE_FEE_DEDUCTED = "fee_deducted";
    public static final String MODE_FULL = "full";
    public static final String MODE_MANUAL = "manual";

    /** ใส่ใน tb_refunds.stripe_refund_id (NOT NULL) ของรายการที่แอดมินโอนคืนเอง */
    static final String MANUAL_PAYOUT_REFERENCE = "manual-promptpay-transfer";

    private final StripeService stripeService;
    private final JdbcTemplate jdbcTemplate;

    public RefundService(StripeService stripeService, JdbcTemplate jdbcTemplate) {
        this.stripeService = stripeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ═════════════════════════════════════════════════════════════════
    // คำนวณยอด
    // ═════════════════════════════════════════════════════════════════

    /**
     * คำนวณยอดคืนที่แนะนำ พร้อมรายละเอียดว่าหักอะไรไปบ้าง
     *
     * @param itemsAmountBaht ราคารวมของรายการที่จะยกเลิก (บาท)
     *                        ถ้าเป็นการยกเลิกทั้งใบ ให้ส่ง null
     */
    public Quote quote(OrderEntity order, Double itemsAmountBaht) throws StripeException {
        long alreadyRefunded = refundedMinorUnit(order.getId());
        Charge paid = chargeOf(order, alreadyRefunded);
        long charged = paid.amount();
        long fee = paid.fee();

        if (charged <= 0) {
            throw new RefundException("ยังไม่มีการเรียกเก็บเงินสำหรับคำสั่งซื้อนี้");
        }

        long gross;      // ยอดก่อนหักค่าธรรมเนียม
        long feeShare;   // ค่าธรรมเนียมที่หัก

        if (itemsAmountBaht == null) {
            // ─── ยกเลิกทั้งใบ — คืนค่าส่งด้วย ───
            gross = charged;
            feeShare = fee;
        } else {
            // ─── ยกเลิกบางรายการ — ค่าส่งไม่คืน ───
            gross = Math.round(itemsAmountBaht * 100.0);
            if (gross <= 0) {
                throw new RefundException("ยอดที่ขอยกเลิกต้องมากกว่า 0");
            }
            // หักค่าธรรมเนียมตามสัดส่วน ปัดเป็นจำนวนเต็มสตางค์
            feeShare = Math.round((double) fee * gross / charged);
        }

        long suggested = gross - feeShare;
        long remaining = charged - alreadyRefunded; // เพดานที่ Stripe ยอมให้คืน

        // กันกรณีเคยคืนไปแล้วบางส่วน จนยอดใหม่เกินที่เหลือ
        if (suggested > remaining)
            suggested = remaining;
        if (suggested < 0)
            suggested = 0;

        return new Quote(gross, feeShare, suggested, charged, fee, alreadyRefunded, remaining);
    }

    /** ยอดที่คืนไปแล้วทั้งหมดของ order นี้ (สตางค์) */
    public long refundedMinorUnit(Long orderId) {
        Double sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM tb_refunds WHERE order_id = ?",
                Double.class, orderId);
        return sum == null ? 0L : Math.round(sum * 100.0);
    }

    /** order นี้ต้องให้แอดมินโอนเงินคืนเอง (ไม่ได้จ่ายผ่าน Stripe) */
    public static boolean isManualPayout(OrderEntity order) {
        return PaymentMethods.isPromptPayTransfer(order.getPaymentMethod());
    }

    /** ยอดที่เก็บจากลูกค้าและค่าธรรมเนียม (สตางค์) */
    private record Charge(long amount, long fee) {}

    /**
     * Stripe: ถาม Stripe ตรงๆ
     *
     * โอนพร้อมเพย์: ไม่มีใครเก็บยอดจริงไว้ให้ถาม จึงใช้ ยอดปัจจุบันของ order + ที่คืนไปแล้ว
     * (ยกเลิกบางรายการจะหักยอด order ลงเท่าราคารายการ จึงบวกกลับได้)
     * ถ้าแอดมินเคยคืนน้อยกว่าราคารายการ ผลจะต่ำกว่าจริง — เพดานคืนเงินจึงไม่มีทางเกินที่ได้รับมา
     */
    private Charge chargeOf(OrderEntity order, long alreadyRefunded) throws StripeException {
        if (isManualPayout(order)) {
            long total = Math.round((order.getTotal() == null ? 0.0 : order.getTotal()) * 100.0);
            return new Charge(total + alreadyRefunded, 0L);
        }
        String pi = order.getStripePaymentIntentId();
        if (pi == null || pi.isBlank()) {
            throw new RefundException("คำสั่งซื้อนี้ไม่มีข้อมูลการชำระเงินกับ Stripe");
        }
        return new Charge(stripeService.chargedAmountOf(pi), stripeService.feeOf(pi));
    }

    // ═════════════════════════════════════════════════════════════════
    // ดำเนินการคืนเงิน
    // ═════════════════════════════════════════════════════════════════

    /**
     * คืนเงินจริงผ่าน Stripe แล้วบันทึกลง tb_refunds
     *
     * เรียก Stripe ก่อน แล้วค่อยบันทึก DB — ถ้า Stripe ล้มจะไม่มีบันทึกค้าง
     * ถ้าบันทึก DB ล้มหลังคืนเงินสำเร็จ จะ throw ออกไปให้เห็น
     * และตามเก็บได้จาก Dashboard เพราะ metadata มี orderId อยู่
     *
     * order โอนพร้อมเพย์ไม่เรียก Stripe — บันทึกยอดอย่างเดียว แอดมินโอนคืนเอง
     *
     * @param amountMinorUnit ยอดที่จะคืนจริง (สตางค์)
     * @param mode            fee_deducted | full | manual
     * @return id ของ refund ใน Stripe หรือ MANUAL_PAYOUT_REFERENCE
     */
    @Transactional
    public String execute(OrderEntity order, Long cancelRequestId,
            long amountMinorUnit, long feeDeductedMinorUnit,
            String mode, String reason, String actorEmail) throws StripeException {

        if (amountMinorUnit <= 0) {
            throw new RefundException("ยอดคืนเงินต้องมากกว่า 0");
        }

        long alreadyRefunded = refundedMinorUnit(order.getId());
        long remaining = chargeOf(order, alreadyRefunded).amount() - alreadyRefunded;
        if (amountMinorUnit > remaining) {
            throw new RefundException(String.format(
                    "ยอดคืนเกินที่เหลือคืนได้ (ขอ %.2f บาท เหลือ %.2f บาท)",
                    StripeService.toBaht(amountMinorUnit), StripeService.toBaht(remaining)));
        }

        String reference = isManualPayout(order)
                ? MANUAL_PAYOUT_REFERENCE
                : stripeService.refund(order.getStripePaymentIntentId(), amountMinorUnit, reason).getId();

        jdbcTemplate.update(
                "INSERT INTO tb_refunds (order_id, cancel_request_id, stripe_refund_id, " +
                        "amount, fee_deducted, refund_mode, reason, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                order.getId(), cancelRequestId, reference,
                StripeService.toBaht(amountMinorUnit),
                StripeService.toBaht(feeDeductedMinorUnit),
                mode, reason, actorEmail);

        log.info("💸 order {} คืนเงิน {} บาท (โหมด={}, โดย={})",
                order.getId(), StripeService.toBaht(amountMinorUnit), mode, actorEmail);

        return reference;
    }

    // ═════════════════════════════════════════════════════════════════
    // DTOs
    // ═════════════════════════════════════════════════════════════════

    /**
     * ผลการคำนวณ — ทุกค่าเป็นสตางค์
     *
     * @param gross           ยอดก่อนหักค่าธรรมเนียม
     * @param feeShare        ค่าธรรมเนียมที่หักออก
     * @param suggested       ยอดที่แนะนำให้คืน (gross − feeShare)
     * @param charged         ยอดที่เก็บลูกค้าไปทั้งหมด
     * @param totalFee        ค่าธรรมเนียมทั้งรายการ
     * @param alreadyRefunded คืนไปแล้วเท่าไหร่
     * @param remaining       เพดานที่ยังคืนได้
     */
    public record Quote(
            long gross,
            long feeShare,
            long suggested,
            long charged,
            long totalFee,
            long alreadyRefunded,
            long remaining) {

        public double grossBaht() {
            return StripeService.toBaht(gross);
        }

        public double feeShareBaht() {
            return StripeService.toBaht(feeShare);
        }

        public double suggestedBaht() {
            return StripeService.toBaht(suggested);
        }

        public double remainingBaht() {
            return StripeService.toBaht(remaining);
        }

        /** ยอดถ้าเลือกโหมด "คืนเต็มจำนวน" (ร้านรับค่าธรรมเนียมเอง) */
        public long fullAmount() {
            return Math.min(gross, remaining);
        }

        public double fullAmountBaht() {
            return StripeService.toBaht(fullAmount());
        }
    }

    public static class RefundException extends RuntimeException {
        public RefundException(String message) {
            super(message);
        }
    }
}