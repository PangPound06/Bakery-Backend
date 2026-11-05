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
 * Test PaymentController — QR พร้อมเพย์ของหน้า POS
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}