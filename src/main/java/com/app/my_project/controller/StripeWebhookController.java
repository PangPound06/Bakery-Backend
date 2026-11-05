package com.app.my_project.controller;

import com.app.my_project.service.PaymentStateService;
import com.app.my_project.service.StripeService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * StripeWebhookController — ประตูเดียวที่เปลี่ยนสถานะเป็น "จ่ายแล้ว"
 *
 * ═══ เรื่องที่พลาดกันบ่อย 5 ข้อ ═══
 *
 * 1) ต้องใช้ "raw body" ตอน verify signature
 * ถ้าให้ Spring แปลงเป็น Map/DTO ก่อน แล้วเรา serialize กลับเป็น JSON
 * ลำดับ key จะเปลี่ยน → signature ไม่ตรง → verify fail ตลอด
 * เลยรับเป็น @RequestBody String ตรงๆ
 *
 * 2) ห้ามเชื่อ payload โดยไม่ verify
 * ใครก็ POST มาที่ URL นี้ได้ ถ้าไม่ verify ก็เท่ากับใครก็ตั้ง order
 * เป็น paid ได้ฟรี — แย่กว่าเดิมอีก
 *
 * 3) ห้ามเชื่อผลลัพธ์จากฝั่ง browser
 * ต่อให้ stripe.confirmPayment() ใน JavaScript บอกว่าสำเร็จ
 * หรือ URL มี redirect_status=succeeded ก็ยังไม่ใช่หลักฐาน
 * เพราะทั้งสองอย่างแก้ได้จากฝั่งลูกค้า
 *
 * 4) API version ของบัญชีอาจไม่ตรงกับ library
 * ถ้าไม่ตรง getDataObjectDeserializer().getObject() จะคืน empty
 * ทางแก้คือแกะเอาแค่ id แบบไม่สน version แล้วไป retrieve จาก API เอา
 * เพราะการ retrieve ใช้ version ที่ library รู้จักเสมอ (ดู intent())
 *
 * 5) ต้องตอบ 2xx เร็วๆ
 * ถ้าตอบ error หรือช้าเกิน Stripe จะ retry → งานซ้ำ
 * (กันไว้แล้วด้วย tb_stripe_events)
 */
@RestController
@RequestMapping("/api/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentStateService paymentStateService;
    private final StripeService stripeService;
    private final String webhookSecret;

    public StripeWebhookController(PaymentStateService paymentStateService,
            StripeService stripeService,
            @Value("${stripe.webhook-secret:}") String webhookSecret) {
        this.paymentStateService = paymentStateService;
        this.stripeService = stripeService;
        this.webhookSecret = webhookSecret;
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("⚠️ ยังไม่ได้ตั้ง STRIPE_WEBHOOK_SECRET — webhook จะปฏิเสธทุก request");
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handle(@RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("รับ webhook แต่ยังไม่ได้ตั้ง secret");
            return ResponseEntity.status(503).body("webhook not configured");
        }
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.badRequest().body("missing signature");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            // ไม่ log payload — อาจเป็นคนพยายามยิงมั่ว
            log.warn("signature ไม่ถูกต้อง: {}", e.getMessage());
            return ResponseEntity.status(400).body("invalid signature");
        } catch (Exception e) {
            log.error("อ่าน webhook payload ไม่ได้", e);
            return ResponseEntity.status(400).body("invalid payload");
        }

        // กัน event ซ้ำ ก่อนแตะข้อมูลอะไรทั้งนั้น
        if (!paymentStateService.claimEvent(event.getId(), event.getType())) {
            return ResponseEntity.ok("duplicate ignored");
        }

        try {
            dispatch(event);
        } catch (Exception e) {
            // ตอบ 500 เพื่อให้ Stripe retry
            // ดูรายละเอียดได้ที่ Dashboard > Developers > Webhooks
            log.error("ประมวลผล event {} ({}) ไม่สำเร็จ", event.getId(), event.getType(), e);
            return ResponseEntity.internalServerError().body("processing error");
        }

        return ResponseEntity.ok("ok");
    }

    // ═════════════════════════════════════════════════════════════════
    // EVENT ROUTING
    // ═════════════════════════════════════════════════════════════════

    private void dispatch(Event event) {
        String type = event.getType();
        log.info("📨 Stripe event: {} ({})", type, event.getId());

        switch (type) {
            // ═══ เงินเข้าแล้วจริง — ทั้งบัตรและ PromptPay ═══
            case "payment_intent.succeeded" -> {
                PaymentIntent pi = intent(event);
                if (pi == null)
                    return;
                Long orderId = orderIdOf(pi);
                if (orderId != null)
                    paymentStateService.markPaid(orderId, pi.getId());
            }

            // ═══ จ่ายไม่สำเร็จ — บัตรถูกปฏิเสธ / PromptPay หมดเวลา ═══
            case "payment_intent.payment_failed" -> {
                PaymentIntent pi = intent(event);
                if (pi == null)
                    return;
                Long orderId = orderIdOf(pi);
                if (orderId != null) {
                    String reason = pi.getLastPaymentError() != null
                            ? pi.getLastPaymentError().getMessage()
                            : "การชำระเงินไม่สำเร็จ";
                    paymentStateService.releaseOrder(orderId,
                            PaymentStateService.FAILED, reason);
                }
            }

            // ═══ ถูกยกเลิก (ลูกค้ากดยกเลิก หรือเราสั่ง cancel ตอนเริ่มรอบใหม่) ═══
            case "payment_intent.canceled" -> {
                PaymentIntent pi = intent(event);
                if (pi == null)
                    return;
                Long orderId = orderIdOf(pi);
                if (orderId != null)
                    paymentStateService.releaseOrder(orderId,
                            PaymentStateService.EXPIRED, "ยกเลิกการชำระเงิน");
            }

            default -> log.debug("ไม่ได้จัดการ event ชนิด {}", type);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /**
     * ดึง PaymentIntent จาก event โดยไม่พึ่ง deserializer ของ library เพียงอย่างเดียว
     *
     * ═══ ทำไมต้องมีทางสำรอง ═══
     * บัญชี Stripe แต่ละอันตรึง API version ของตัวเองไว้ (เช่น 2025-12-15.clover)
     * ส่วน stripe-java ก็ตรึงของมันไว้อีกอัน
     * ถ้าสองอันนี้ไม่ตรงกัน getObject() จะคืน empty
     *
     * ทางสำรองคือ deserializeUnsafe() เพื่อเอาแค่ id แล้วเรียก API ดึงข้อมูลจริงมา
     * เพราะการ retrieve จะได้ข้อมูลในรูปแบบที่ library รู้จักเสมอ
     * วิธีนี้ทนต่อความต่างของ version ทุกกรณี ไม่ต้องไปอัปเกรดบัญชี
     */
    private PaymentIntent intent(Event event) {
        EventDataObjectDeserializer data = event.getDataObjectDeserializer();

        // ทางหลัก — เร็วกว่า ใช้ได้เมื่อ version ตรงกัน
        if (data.getObject().orElse(null) instanceof PaymentIntent pi) {
            return pi;
        }

        // ทางสำรอง — อ่าน id แล้วไปถาม Stripe เอง
        try {
            if (data.deserializeUnsafe() instanceof PaymentIntent stale) {
                log.info("API version ไม่ตรง — ดึง {} จาก Stripe API แทน", stale.getId());
                return stripeService.retrieveIntent(stale.getId());
            }
            log.warn("event {} ไม่ได้แนบ PaymentIntent", event.getId());
            return null;
        } catch (StripeException e) {
            log.error("ดึง PaymentIntent จาก event {} ไม่สำเร็จ", event.getId(), e);
            return null;
        }
    }

    private Long orderIdOf(PaymentIntent pi) {
        String raw = pi.getMetadata() == null ? null : pi.getMetadata().get("orderId");
        try {
            return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.error("intent {} มี orderId ที่อ่านไม่ออก: {}", pi.getId(), raw);
            return null;
        }
    }
}