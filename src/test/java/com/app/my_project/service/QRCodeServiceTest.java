package com.app.my_project.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test QRCodeService — ตรวจโครงสร้าง PromptPay payload (EMVCo) และการสร้าง QR เป็น PNG/Base64
 * ไม่มี dependency ภายนอกให้ mock (เป็น logic ล้วน + ZXing)
 */
class QRCodeServiceTest {

    private final QRCodeService service = new QRCodeService("0931253748", "Pound Bakery");

    @Test
    @DisplayName("payload: ตรงกับ QR ที่ร้านใช้อยู่ทุกตัวอักษร (รวม CRC)")
    void payloadMatchesKnownQr() {
        assertThat(service.generatePromptPayPayload(100.0)).isEqualTo(
                "00020101021229370016A0000006770101110113006693125374853037645406100.005802TH63046A3C");
    }

    @Test
    @DisplayName("payload: มี EMVCo field ครบและลงท้ายด้วย CRC 4 หลัก")
    void payloadHasRequiredFields() {
        String p = service.generatePromptPayPayload(100.0);

        assertThat(p).startsWith("000201010212");      // format + dynamic QR
        assertThat(p).contains("5303764");              // currency THB
        assertThat(p).contains("5802TH");               // country
        assertThat(p).contains("A000000677010111");     // PromptPay AID
        assertThat(p).contains("0066931253748");        // เบอร์ 0931253748 → 0066+931253748
        assertThat(p).contains("5406100.00");           // amount tag(54) len(06) ค่า 100.00
        assertThat(p).matches(".*6304[0-9A-F]{4}$");    // ลงท้ายด้วย CRC tag + 4 hex
    }

    @Test
    @DisplayName("payload: amount = 0 → QR ถาวร (11) และไม่มีฟิลด์จำนวนเงิน")
    void payloadOmitsAmountWhenZero() {
        String p = new QRCodeService("1234567890123", "").generatePromptPayPayload(0);

        assertThat(p).isEqualTo(
                "00020101021129370016A0000006770101110213123456789012353037645802TH630433FC");
    }

    @Test
    @DisplayName("payload: ปัดยอดเป็นทศนิยม 2 ตำแหน่งเสมอ")
    void payloadRoundsAmount() {
        assertThat(service.generatePromptPayPayload(99.999)).contains("5406100.00");
        assertThat(service.generatePromptPayPayload(12.5)).contains("540512.50");
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

    @Test
    @DisplayName("เลขพร้อมเพย์: รับขีด/ช่องว่าง แต่แสดงให้ลูกค้าเห็นแค่ 4 ตัวท้าย")
    void masksPromptPayId() {
        QRCodeService withDashes = new QRCodeService("093-125-3748", " ร้าน ");

        assertThat(withDashes.maskedPromptPayId()).isEqualTo("xxxxxx3748");
        assertThat(withDashes.accountName()).isEqualTo("ร้าน");
    }

    @Test
    @DisplayName("เลขพร้อมเพย์ผิดรูปแบบ → แอปไม่ยอมเริ่ม")
    void rejectsInvalidPromptPayId() {
        assertThatThrownBy(() -> new QRCodeService("", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new QRCodeService("12345", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new QRCodeService("1931253748", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new QRCodeService("09312537ab", "")).isInstanceOf(IllegalStateException.class);
    }
}
