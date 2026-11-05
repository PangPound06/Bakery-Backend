package com.app.my_project.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test SlipQrReader — รูปที่มี QR ของสลิปเท่านั้นที่ผ่าน
 */
class SlipQrReaderTest {

    /** ตัวอย่างข้อความ QR ตรวจสอบสลิปของธนาคารไทย (ไม่ขึ้นต้นด้วย 000201) */
    private static final String SLIP_PAYLOAD = "004600060000010103014022520251105231545ABCD12345678905102TH9104A1B2";

    private final SlipQrReader reader = new SlipQrReader();

    /** QR วางกลางพื้นขาวขนาดใหญ่ — คล้ายภาพหน้าจอสลิป */
    private static byte[] slipImage(String qrText) throws Exception {
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(
                new QRCodeWriter().encode(qrText, BarcodeFormat.QR_CODE, 240, 240));
        BufferedImage page = new BufferedImage(600, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 1000);
        g.drawImage(qr, 330, 700, null);
        g.dispose();
        return png(page);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("สลิปที่มี QR → OK พร้อม reference คงที่ (QR เดิม = reference เดิม)")
    void slipWithQr_ok() throws Exception {
        SlipQrReader.Result first = reader.read(slipImage(SLIP_PAYLOAD));
        SlipQrReader.Result again = reader.read(slipImage(SLIP_PAYLOAD));

        assertThat(first.ok()).isTrue();
        assertThat(first.reference()).startsWith("slip:").hasSize(5 + 64).isEqualTo(again.reference());
    }

    @Test
    @DisplayName("สลิปคนละใบ → reference ต่างกัน")
    void differentSlips_differentReference() throws Exception {
        assertThat(reader.read(slipImage(SLIP_PAYLOAD)).reference())
                .isNotEqualTo(reader.read(slipImage(SLIP_PAYLOAD + "X")).reference());
    }

    @Test
    @DisplayName("รูปไม่มี QR → NO_QR")
    void noQr() throws Exception {
        BufferedImage blank = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        assertThat(reader.read(png(blank)).verdict()).isEqualTo(SlipQrReader.Verdict.NO_QR);
    }

    @Test
    @DisplayName("QR พร้อมเพย์สำหรับจ่ายเงิน (ของร้าน) → PAYMENT_QR ไม่ใช่สลิป")
    void paymentQr_rejected() throws Exception {
        String shopQr = new QRCodeService("0931253748", "").generatePromptPayPayload(155);

        assertThat(reader.read(slipImage(shopQr)).verdict()).isEqualTo(SlipQrReader.Verdict.PAYMENT_QR);
    }

    @Test
    @DisplayName("ไฟล์ไม่ใช่รูป → UNREADABLE")
    void notAnImage() {
        assertThat(reader.read("not an image".getBytes()).verdict()).isEqualTo(SlipQrReader.Verdict.UNREADABLE);
    }
}
