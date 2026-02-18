package com.app.my_project.controller;

import com.app.my_project.service.QRCodeService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
@CrossOrigin(origins = "*")
public class PaymentController {

    @Autowired
    private QRCodeService qrCodeService;

    // QR PROMPTPAY
    @PostMapping("/promptpay/generate")
    public ResponseEntity<?> generatePromptPayQR(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Double amount = Double.parseDouble(request.get("amount").toString());
            String qrCodeBase64 = qrCodeService.generateQRCodeBase64(amount);
            response.put("success", true);
            response.put("qrCodeBase64", qrCodeBase64);
            response.put("amount", amount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "ไม่สามารถสร้าง QR Code ได้: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // CARD PAYMENT (Test Mode - จำลอง)
    @PostMapping("/card/charge")
    public ResponseEntity<?> chargeCard(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Double amount = Double.parseDouble(request.get("amount").toString());
            String cardNumber = request.get("cardNumber").toString().replaceAll("\\s", "");
            String expMonth = request.get("expMonth").toString();
            String cvc = request.get("cvc").toString();
            String cardName = request.getOrDefault("cardName", "").toString();

            // Validation
            if (cardNumber.length() < 13 || cardNumber.length() > 19) {
                response.put("success", false);
                response.put("message", "หมายเลขบัตรไม่ถูกต้อง");
                return ResponseEntity.badRequest().body(response);
            }

            int expM = Integer.parseInt(expMonth);
            if (expM < 1 || expM > 12) {
                response.put("success", false);
                response.put("message", "เดือนหมดอายุไม่ถูกต้อง");
                return ResponseEntity.badRequest().body(response);
            }

            if (cvc.length() < 3 || cvc.length() > 4) {
                response.put("success", false);
                response.put("message", "CVC ไม่ถูกต้อง");
                return ResponseEntity.badRequest().body(response);
            }

            // จำลองชำระเงินสำเร็จ
            String last4 = cardNumber.substring(cardNumber.length() - 4);
            String paymentId = "PAY_" + System.currentTimeMillis();

            response.put("success", true);
            response.put("paymentId", paymentId);
            response.put("cardLast4", last4);
            response.put("cardName", cardName);
            response.put("amount", amount);
            response.put("message", "ชำระเงินสำเร็จ");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}