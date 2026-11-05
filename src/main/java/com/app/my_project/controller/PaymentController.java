package com.app.my_project.controller;

import com.app.my_project.service.QRCodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * QR พร้อมเพย์ของร้านสำหรับหน้า POS ของแอดมิน (จ่ายหน้าเคาน์เตอร์)
 *
 * ลูกค้าออนไลน์และบิลหน้าโต๊ะจ่ายผ่าน Stripe ทั้งหมด — ดู CheckoutController
 * และ DineInOrderController.requestBill
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final QRCodeService qrCodeService;

    public PaymentController(QRCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    @PostMapping("/promptpay/generate")
    public ResponseEntity<?> generatePromptPayQR(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            double amount = Double.parseDouble(request.get("amount").toString());
            response.put("success", true);
            response.put("qrCodeBase64", qrCodeService.generateQRCodeBase64(amount));
            response.put("amount", amount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "ไม่สามารถสร้าง QR Code ได้: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
