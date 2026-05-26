package com.app.my_project.controller;

import com.app.my_project.service.QRCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * Test PaymentController
 *
 * Focus: input validation ที่สำคัญ (ปลอดภัยทางการเงิน)
 *   - card number length 13-19
 *   - exp month 1-12
 *   - cvc length 3-4
 *   - return cardLast4 (ไม่ leak full number)
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock private QRCodeService qrCodeService;

    @InjectMocks
    private PaymentController controller;

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/payment/promptpay/generate")
    class PromptPayTests {

        @Test
        @DisplayName("สร้าง QR สำเร็จ → 200 + base64")
        void generateQR_validAmount_returns200() throws Exception {
            when(qrCodeService.generateQRCodeBase64(anyDouble()))
                    .thenReturn("base64-qr-data");

            Map<String, Object> req = new HashMap<>();
            req.put("amount", 150.5);

            ResponseEntity<?> response = controller.generatePromptPayQR(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsKey("qrCodeBase64");
            assertThat(body).containsEntry("amount", 150.5);
        }

        @Test
        @DisplayName("amount เป็น null → 400")
        void generateQR_missingAmount_returns400() {
            Map<String, Object> req = new HashMap<>();
            // ไม่ใส่ amount

            ResponseEntity<?> response = controller.generatePromptPayQR(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/payment/card/charge — validation")
    class CardChargeTests {

        private Map<String, Object> validCardRequest() {
            Map<String, Object> req = new HashMap<>();
            req.put("amount", 1000.0);
            req.put("cardNumber", "4242424242424242"); // 16 digits
            req.put("expMonth", "12");
            req.put("cvc", "123");
            req.put("cardName", "John Doe");
            return req;
        }

        @Test
        @DisplayName("Happy path: ชำระสำเร็จ → 200 + paymentId + cardLast4")
        void charge_validCard_returns200() {
            ResponseEntity<?> response = controller.chargeCard(validCardRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("cardLast4", "4242"); // เก็บแค่ 4 หลัก
            assertThat((String) body.get("paymentId")).startsWith("PAY_");
            // ไม่มี cardNumber เต็มใน response (ความปลอดภัย)
            assertThat(body).doesNotContainKey("cardNumber");
        }

        @Test
        @DisplayName("card number สั้นเกิน (< 13) → 400")
        void charge_cardNumberTooShort_returns400() {
            Map<String, Object> req = validCardRequest();
            req.put("cardNumber", "1234567890"); // 10 digits

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("บัตร");
        }

        @Test
        @DisplayName("card number ยาวเกิน (> 19) → 400")
        void charge_cardNumberTooLong_returns400() {
            Map<String, Object> req = validCardRequest();
            req.put("cardNumber", "12345678901234567890"); // 20 digits

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("expMonth = 13 (>12) → 400")
        void charge_invalidExpMonth_returns400() {
            Map<String, Object> req = validCardRequest();
            req.put("expMonth", "13");

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("เดือน");
        }

        @Test
        @DisplayName("expMonth = 0 → 400")
        void charge_expMonthZero_returns400() {
            Map<String, Object> req = validCardRequest();
            req.put("expMonth", "0");

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("CVC สั้นเกิน (2 หลัก) → 400")
        void charge_cvcTooShort_returns400() {
            Map<String, Object> req = validCardRequest();
            req.put("cvc", "12");

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("CVC");
        }

        @Test
        @DisplayName("CVC ยาวเกิน (5 หลัก) → 400")
        void charge_cvcTooLong_returns400() {
            Map<String, Object> req = validCardRequest();
            req.put("cvc", "12345");

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("card number มี space ก็ใช้งานได้ (strip ออก)")
        void charge_cardNumberWithSpaces_isStripped() {
            Map<String, Object> req = validCardRequest();
            req.put("cardNumber", "4242 4242 4242 4242"); // มี space

            ResponseEntity<?> response = controller.chargeCard(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(castBody(response)).containsEntry("cardLast4", "4242");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}