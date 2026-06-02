package com.app.my_project.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test QRCodeService — ตรวจโครงสร้าง PromptPay payload (EMVCo) และการสร้าง QR เป็น PNG/Base64
 * ไม่มี dependency ภายนอกให้ mock (เป็น logic ล้วน + ZXing)
 */
class QRCodeServiceTest {

    private final QRCodeService service = new QRCodeService();

    @Test
    @DisplayName("payload: มี EMVCo field ครบและลงท้ายด้วย CRC 4 หลัก")
    void payloadHasRequiredFields() {
        String p = service.generatePromptPayPayload(100.0);

        assertThat(p).startsWith("000201010212");      // format + dynamic QR
        assertThat(p).contains("5303764");              // currency THB
        assertThat(p).contains("5802TH");               // country
        assertThat(p).contains("A000000677010111");     // PromptPay AID
        assertThat(p).contains("0066931253748");         // เบอร์ 0931253748 → 0066+931253748
        assertThat(p).contains("5406100.00");             // amount tag(54) len(06) ค่า 100.00
        assertThat(p).matches(".*6304[0-9A-F]{4}$");      // ลงท้ายด้วย CRC tag + 4 hex
    }

    @Test
    @DisplayName("payload: amount = 0 → ไม่มีฟิลด์จำนวนเงิน")
    void payloadOmitsAmountWhenZero() {
        String p = service.generatePromptPayPayload(0);

        assertThat(p).doesNotContain(".00");  // ไม่มีค่าจำนวนเงิน
        assertThat(p).contains("5802TH");
        assertThat(p).matches(".*6304[0-9A-F]{4}$");
    }

    @Test
    @DisplayName("payload: จำนวนเงินต่างกัน → payload ต่างกัน")
    void payloadDiffersByAmount() {
        assertThat(service.generatePromptPayPayload(100.0))
                .isNotEqualTo(service.generatePromptPayPayload(200.0));
    }

    @Test
    @DisplayName("generateQRCodeBase64: คืน Base64 ของรูป PNG ที่ถูกต้อง")
    void qrCodeBase64IsValidPng() throws Exception {
        String b64 = service.generateQRCodeBase64(150.0);

        assertThat(b64).isNotBlank();
        byte[] png = Base64.getDecoder().decode(b64);

        assertThat(png.length).isGreaterThan(8);
        // PNG signature: 89 50('P') 4E('N') 47('G')
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }
}