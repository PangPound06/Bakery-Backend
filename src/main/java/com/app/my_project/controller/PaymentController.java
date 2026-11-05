package com.app.my_project.controller;

import com.app.my_project.service.QRCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * QR พร้อมเพย์ของร้านสำหรับหน้า POS ของแอดมิน (จ่ายหน้าเคาน์เตอร์)
 *
 * ลูกค้าออนไลน์จ่ายผ่าน CheckoutController และบิลหน้าโต๊ะผ่าน DineInOrderController.requestBill
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    /** กันพิมพ์ศูนย์เกิน — ยอดหน้าร้านจริงไม่ถึงหลักล้าน */
    private static final double MAX_AMOUNT = 1_000_000;

    private final QRCodeService qrCodeService;

    public PaymentController(QRCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    @PostMapping("/promptpay/generate")
    public ResponseEntity<?> generatePromptPayQR(@RequestBody Map<String, Object> request) {
        Double amount = parseAmount(request.get("amount"));
        if (amount == null || !Double.isFinite(amount) || amount <= 0 || amount > MAX_AMOUNT) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "จำนวนเงินไม่ถูกต้อง"));
        }
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "qrCodeBase64", qrCodeService.generateQRCodeBase64(amount),
                    "amount", amount));
        } catch (Exception e) {
            log.error("สร้าง QR พร้อมเพย์ไม่สำเร็จ (amount={})", amount, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "ไม่สามารถสร้าง QR Code ได้"));
        }
    }

    private static Double parseAmount(Object raw) {
        if (raw instanceof Number n)
            return n.doubleValue();
        if (raw == null)
            return null;
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
