package com.app.my_project.service;

import com.app.my_project.common.CheckoutMethod;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.OrderRepository;
import com.google.zxing.WriterException;
import com.stripe.exception.StripeException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ออกช่องทางจ่ายเงินให้ order ที่เตรียมไว้แล้ว (awaiting_payment)
 *
 * ใช้ร่วมกันระหว่างหน้า checkout ออนไลน์ และบิลหน้าร้าน — หน้าเว็บทั้งสองฝั่งจึงใช้ panel ชุดเดียวกันได้
 *
 *  promptpay → QR ของ Stripe (qrImageUrl, hostedUrl, testMode)
 *  card      → client_secret ของ Stripe (clientSecret, testMode)
 *  transfer  → QR พร้อมเพย์ของร้าน (qrImageUrl, promptPayId, accountName, expiresInSeconds)
 */
@Service
public class PaymentStarter {

    private final StripeService stripeService;
    private final QRCodeService qrCodeService;
    private final OrderRepository orderRepository;

    public PaymentStarter(StripeService stripeService, QRCodeService qrCodeService,
            OrderRepository orderRepository) {
        this.stripeService = stripeService;
        this.qrCodeService = qrCodeService;
        this.orderRepository = orderRepository;
    }

    /**
     * @param total ยอดที่ server คำนวณเอง — ห้ามส่งตัวเลขจาก client เข้ามา
     * @return ฟิลด์ที่หน้าเว็บใช้แสดงช่องทางจ่ายเงิน
     */
    public Map<String, Object> start(OrderEntity order, double total, String email, CheckoutMethod method)
            throws StripeException, WriterException, IOException {
        Map<String, Object> resp = new HashMap<>();
        switch (method) {
            case PROMPTPAY -> {
                StripeService.PromptPayQr qr = stripeService.createPromptPayIntent(order, total, email);
                attachIntent(order, qr.paymentIntentId());
                resp.put("qrImageUrl", qr.qrImageUrl());
                resp.put("hostedUrl", qr.hostedInstructionsUrl() == null ? "" : qr.hostedInstructionsUrl());
                // คีย์ sk_test → หน้าเว็บแสดงลิงก์จำลองการจ่ายเงิน เพราะแอปธนาคารสแกน QR ทดสอบไม่ได้
                resp.put("testMode", stripeService.isTestMode());
            }
            case CARD -> {
                StripeService.CardIntent ci = stripeService.createCardIntent(order, total, email);
                attachIntent(order, ci.paymentIntentId());
                resp.put("clientSecret", ci.clientSecret());
                resp.put("testMode", stripeService.isTestMode());
            }
            case TRANSFER -> {
                // QR ระบุยอดตรงกับ order — สแกนด้วยแอปธนาคารจริงได้ทันที
                resp.put("qrImageUrl", "data:image/png;base64," + qrCodeService.generateQRCodeBase64(total));
                resp.put("promptPayId", qrCodeService.maskedPromptPayId());
                resp.put("accountName", qrCodeService.accountName());
                resp.put("expiresInSeconds", PaymentStateService.TRANSFER_PAY_WINDOW.toSeconds());
            }
        }
        return resp;
    }

    private void attachIntent(OrderEntity order, String paymentIntentId) {
        order.setStripePaymentIntentId(paymentIntentId);
        orderRepository.save(order);
    }
}
