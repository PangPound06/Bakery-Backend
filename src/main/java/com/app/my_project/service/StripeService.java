package com.app.my_project.service;

import com.app.my_project.entity.OrderEntity;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * StripeService — คุยกับ Stripe API อย่างเดียว ไม่ยุ่งกับ DB
 *
 * ═══ เปลี่ยนจาก Checkout Session มาเป็น PaymentIntent ═══
 * แบบเดิม (Checkout Session) ต้อง redirect ลูกค้าออกไปหน้าที่ Stripe โฮสต์
 * แบบใหม่นี้ Stripe คืน "วัตถุดิบ" มาให้ แล้วเราวาดหน้าเอง ลูกค้าไม่ต้องออกจากเว็บ
 *
 * PromptPay → คืน URL รูป QR มาให้ เอาไปใส่ img ได้เลย
 * บัตร → คืน client_secret ให้ frontend เอาไปผูกกับ Stripe.js
 *
 * ═══ เลขบัตรยังไม่ผ่าน server ของเรา ═══
 * ช่องกรอกบัตรเป็น iframe ของ Stripe ที่ฝังในหน้าเรา ข้อมูลวิ่งตรงไป Stripe
 * เราเห็นแค่ client_secret ซึ่งไม่ใช่ข้อมูลบัตร จึงยังไม่ติด PCI-DSS
 *
 * ═══ ทำไมเรียกผ่าน v1() ═══
 * stripe-java เลิกใช้ client.paymentIntents() แล้ว ย้ายไป client.v1().paymentIntents()
 * ทุกอย่างใต้ v1() เหมือนเดิม เปลี่ยนแค่ทางเข้า
 *
 * ═══ หน่วยเงิน ═══
 * Stripe รับเป็นจำนวนเต็มหน่วยย่อย → THB ใช้ "สตางค์"
 * 350.50 บาท = 35050
 */
@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    public static final String METHOD_PROMPTPAY = "promptpay";
    public static final String METHOD_CARD = "card";

    private final StripeClient client;
    private final String currency;
    private final String frontendUrl;
    private final boolean enabled;

    /** true เมื่อใช้คีย์ sk_test — หน้าเว็บใช้ตัดสินว่าจะโชว์ลิงก์หน้าจำลองไหม */
    private final boolean testMode;

    public StripeService(
            @Value("${stripe.secret-key:}") String secretKey,
            @Value("${stripe.currency:thb}") String currency,
            @Value("${frontend.url:http://localhost:3000}") String frontendUrl) {

        this.enabled = secretKey != null && !secretKey.isBlank();
        this.testMode = secretKey != null && secretKey.startsWith("sk_test");
        this.client = enabled ? new StripeClient(secretKey) : null;
        this.currency = currency;
        this.frontendUrl = stripTrailingSlash(frontendUrl);

        if (!enabled) {
            log.warn("⚠️ ยังไม่ได้ตั้ง STRIPE_SECRET_KEY — endpoint ชำระเงินจะตอบ 503");
        } else {
            log.info("✅ Stripe พร้อมใช้งาน (currency={}, frontend={})", currency, this.frontendUrl);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isTestMode() {
        return testMode;
    }

    // ═════════════════════════════════════════════════════════════════
    // PROMPTPAY — ได้ QR กลับมาแสดงในเว็บเราเลย
    // ═════════════════════════════════════════════════════════════════

    /**
     * สร้าง PaymentIntent แบบ PromptPay แล้ว confirm ทันทีเพื่อให้ Stripe ออก QR
     *
     * Stripe จะคืนสถานะ requires_action พร้อม next_action.promptpay_display_qr_code
     * ซึ่งมี URL รูป PNG ให้เอาไปแสดงได้ตรงๆ
     *
     * หมายเหตุ: PromptPay ยืนยันแบบ asynchronous — ตอนได้ QR ยังไม่ได้เงิน
     * ต้องรอ webhook payment_intent.succeeded เท่านั้นถึงจะถือว่าจ่ายแล้ว
     */
    public PromptPayQr createPromptPayIntent(OrderEntity order,
            double amountInBaht,
            String customerEmail) throws StripeException {

        requireEnabled();

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnit(amountInBaht))
                .setCurrency(currency)
                .addPaymentMethodType(METHOD_PROMPTPAY)
                .setPaymentMethodData(PaymentIntentCreateParams.PaymentMethodData.builder()
                        .setType(PaymentIntentCreateParams.PaymentMethodData.Type.PROMPTPAY)
                        .setBillingDetails(
                                PaymentIntentCreateParams.PaymentMethodData.BillingDetails.builder()
                                        .setEmail(customerEmail)
                                        .build())
                        .build())
                // confirm ทันที = Stripe ออก QR ให้เลย ไม่ต้องรอ frontend ยืนยัน
                .setConfirm(true)
                .setReturnUrl(successUrl(order))
                .setReceiptEmail(customerEmail)
                .setDescription(orderLabel(order))
                .putMetadata("orderId", String.valueOf(order.getId()))
                .putMetadata("ordCode", nullSafe(order.getOrdCode()))
                .build();

        PaymentIntent intent = client.v1().paymentIntents().create(params);

        String png = null;
        String raw = null;
        String hosted = null;
        if (intent.getNextAction() != null
                && intent.getNextAction().getPromptpayDisplayQrCode() != null) {
            // ประกาศตัวแปร qr ก่อนใช้ ไม่งั้น compiler หาไม่เจอ
            PaymentIntent.NextAction.PromptpayDisplayQrCode qr =
                    intent.getNextAction().getPromptpayDisplayQrCode();
            png = qr.getImageUrlPng();
            raw = qr.getData();
            // ใน test mode ลิงก์นี้พาไปหน้าจำลองของ Stripe ที่กดให้จ่ายสำเร็จ/ล้มเหลวได้
            // ใน live mode เป็นหน้าแสดง QR สำรองของ Stripe
            hosted = qr.getHostedInstructionsUrl();
        }

        if (png == null) {
            // ไม่ควรเกิด — ถ้าเกิดแปลว่า PromptPay ยังไม่ถูกเปิดใน Dashboard
            // หรือสกุลเงินไม่ใช่ THB
            log.error("Stripe ไม่คืน QR สำหรับ order {} (status={})", order.getId(), intent.getStatus());
            throw new IllegalStateException("ไม่สามารถสร้าง QR PromptPay ได้");
        }

        log.info("สร้าง PromptPay QR สำหรับ order {} ยอด {} {} (pi={})",
                order.getId(), amountInBaht, currency.toUpperCase(), intent.getId());

        return new PromptPayQr(intent.getId(), png, raw, hosted);
    }

    // ═════════════════════════════════════════════════════════════════
    // บัตรเครดิต/เดบิต — คืน client_secret ให้ frontend ผูกกับ Stripe.js
    // ═════════════════════════════════════════════════════════════════

    /**
     * สร้าง PaymentIntent แบบบัตร (ยังไม่ confirm)
     *
     * frontend จะเอา client_secret ไปสร้างช่องกรอกบัตรของ Stripe
     * แล้วเรียก stripe.confirmPayment() เอง — เลขบัตรไม่ผ่าน server เรา
     *
     * @param amountInBaht ยอดที่ server คำนวณเอง (ตะกร้าออนไลน์ หรือบิลหน้าร้าน) — ห้ามรับจาก client
     */
    public CardIntent createCardIntent(OrderEntity order,
            double amountInBaht,
            String customerEmail) throws StripeException {

        requireEnabled();

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnit(amountInBaht))
                .setCurrency(currency)
                .addPaymentMethodType(METHOD_CARD)
                .setReceiptEmail(customerEmail)
                .setDescription(orderLabel(order))
                .putMetadata("orderId", String.valueOf(order.getId()))
                .putMetadata("ordCode", nullSafe(order.getOrdCode()))
                .build();

        PaymentIntent intent = client.v1().paymentIntents().create(params);

        log.info("สร้าง Card intent สำหรับ order {} ยอด {} {} (pi={})",
                order.getId(), amountInBaht, currency.toUpperCase(), intent.getId());

        return new CardIntent(intent.getId(), intent.getClientSecret());
    }

    // ═════════════════════════════════════════════════════════════════
    // จัดการ intent
    // ═════════════════════════════════════════════════════════════════

    public PaymentIntent retrieveIntent(String paymentIntentId) throws StripeException {
        requireEnabled();
        return client.v1().paymentIntents().retrieve(paymentIntentId);
    }

    /**
     * ยกเลิก intent ที่ยังไม่จ่าย — ใช้ตอนลูกค้ากดสั่งใหม่ทับของเดิม
     *
     * @return true = ยกเลิกสำเร็จ, false = ยกเลิกไม่ได้ (เช่น จ่ายไปแล้วหรือถูกยกเลิกไปก่อนแล้ว)
     */
    public boolean cancelIntent(String paymentIntentId) {
        if (!enabled || paymentIntentId == null || paymentIntentId.isBlank())
            return false;
        try {
            client.v1().paymentIntents().cancel(paymentIntentId);
            return true;
        } catch (StripeException e) {
            log.debug("cancel intent {} ไม่สำเร็จ: {}", paymentIntentId, e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // REFUND — คืนเงิน (เต็มจำนวนหรือบางส่วน)
    // ═════════════════════════════════════════════════════════════════

    /**
     * ค่าธรรมเนียมจริงที่ Stripe หักจากรายการนี้ (หน่วย: สตางค์)
     *
     * ═══ ทำไมต้องถามทุกครั้ง ไม่ hardcode % ═══
     * อัตราค่าธรรมเนียมต่างกันตามวิธีจ่าย (บัตรในประเทศ / ต่างประเทศ / PromptPay)
     * และเปลี่ยนได้เมื่อ Stripe ปรับราคา หรือเมื่อบัญชีเปลี่ยนจาก sandbox เป็น live
     * ถ้าเขียนเลขตายตัวไว้ วันหนึ่งจะคำนวณผิดโดยไม่มีใครรู้
     *
     * เส้นทาง: PaymentIntent → latest_charge → balance_transaction → fee
     *
     * @return ค่าธรรมเนียมเป็นสตางค์ หรือ 0 ถ้าหาไม่เจอ
     */
    public long feeOf(String paymentIntentId) throws StripeException {
        requireEnabled();

        PaymentIntent pi = client.v1().paymentIntents().retrieve(paymentIntentId);
        String chargeId = pi.getLatestCharge();
        if (chargeId == null || chargeId.isBlank()) {
            log.warn("intent {} ยังไม่มี charge — คิดค่าธรรมเนียมเป็น 0", paymentIntentId);
            return 0L;
        }

        Charge charge = client.v1().charges().retrieve(chargeId);
        String btId = charge.getBalanceTransaction();
        if (btId == null || btId.isBlank()) {
            // เกิดได้ถ้ารายการยังไม่ settle — ไม่ควรหักค่าธรรมเนียมที่ยังไม่รู้ค่า
            log.warn("charge {} ยังไม่มี balance_transaction — คิดค่าธรรมเนียมเป็น 0", chargeId);
            return 0L;
        }

        BalanceTransaction bt = client.v1().balanceTransactions().retrieve(btId);
        long fee = bt.getFee() == null ? 0L : bt.getFee();
        log.info("ค่าธรรมเนียมของ {} = {} สตางค์", paymentIntentId, fee);
        return fee;
    }

    /** ยอดที่เก็บลูกค้าไปจริง (สตางค์) — ใช้เป็นเพดานของการคืนเงิน */
    public long chargedAmountOf(String paymentIntentId) throws StripeException {
        requireEnabled();
        PaymentIntent pi = client.v1().paymentIntents().retrieve(paymentIntentId);
        return pi.getAmountReceived() == null ? 0L : pi.getAmountReceived();
    }

    /**
     * คืนเงิน
     *
     * @param amountInMinorUnit จำนวนสตางค์ที่จะคืน — null = คืนเต็มจำนวนที่เหลือ
     *
     * Stripe คุมเพดานให้อยู่แล้ว (คืนรวมเกินยอดที่เก็บไม่ได้) แต่เราเช็คซ้ำ
     * ฝั่งเราก่อนเรียกด้วย จะได้ตอบ error ที่อ่านรู้เรื่องแทน error ดิบจาก API
     */
    public Refund refund(String paymentIntentId, Long amountInMinorUnit, String reason)
            throws StripeException {

        requireEnabled();

        RefundCreateParams.Builder params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);

        if (amountInMinorUnit != null) {
            params.setAmount(amountInMinorUnit);
        }
        if (reason != null && !reason.isBlank()) {
            params.putMetadata("note", reason.length() > 480 ? reason.substring(0, 480) : reason);
        }

        Refund refund = client.v1().refunds().create(params.build());
        log.info("คืนเงิน {} สตางค์ จาก {} (refund={})",
                refund.getAmount(), paymentIntentId, refund.getId());
        return refund;
    }

    // ─── helpers ───────────────────────────────────────────────

    /** สตางค์ → บาท */
    public static double toBaht(long minorUnit) {
        return minorUnit / 100.0;
    }

    private void requireEnabled() {
        if (!enabled)
            throw new IllegalStateException("ยังไม่ได้ตั้งค่า Stripe secret key");
    }

    /** บาท → สตางค์ (จำนวนเต็ม) */
    static long toMinorUnit(double amountInBaht) {
        return Math.round(amountInBaht * 100.0);
    }

    private String successUrl(OrderEntity order) {
        return frontendUrl + "/checkout/success?order_id=" + order.getId();
    }

    private static String orderLabel(OrderEntity order) {
        String code = order.getOrdCode();
        return "Pound Bakery " + (code == null || code.isBlank() ? "#" + order.getId() : code);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null)
            return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ─── DTOs ──────────────────────────────────────────────────

    /**
     * @param qrImageUrl           URL รูป PNG จาก Stripe เอาไปใส่ src ของ img ได้เลย
     * @param qrData               ข้อความ EMV ดิบ เผื่ออยากวาด QR เองด้วย library
     * @param hostedInstructionsUrl หน้าของ Stripe — test mode ใช้จำลองการจ่ายเงิน
     */
    public record PromptPayQr(String paymentIntentId, String qrImageUrl,
            String qrData, String hostedInstructionsUrl) {
    }

    public record CardIntent(String paymentIntentId, String clientSecret) {
    }
}