package com.app.my_project.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.cloudinary.utils.ObjectUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test SlipController — validate ไฟล์, อัปโหลดสำเร็จ (mock Cloudinary),
 * และอ่าน QR จากสลิป (สร้างรูป QR/รูปเปล่าจริงด้วย ZXing + ImageIO)
 */
class SlipControllerTest {

    private SlipController controller;

    @BeforeEach
    void setUp() {
        controller = new SlipController();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> r) {
        return (Map<String, Object>) r.getBody();
    }

    private byte[] blankPng() throws Exception {
        BufferedImage img = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        Graphics g = img.getGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 120);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        return bos.toByteArray();
    }

    private byte[] qrPng(String data) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 250, 250);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", bos);
        return bos.toByteArray();
    }

    // ── uploadSlip ──────────────────────────────────────────

    @Test
    @DisplayName("uploadSlip: ไฟล์ว่าง → 400")
    void uploadEmpty() {
        MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[0]);
        ResponseEntity<?> r = controller.uploadSlip(file);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(body(r).get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("uploadSlip: ไม่ใช่รูปภาพ → 400")
    void uploadNotImage() {
        MockMultipartFile file =
                new MockMultipartFile("file", "s.txt", "text/plain", "hello".getBytes());
        ResponseEntity<?> r = controller.uploadSlip(file);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("uploadSlip: ไฟล์เกิน 10MB → 400")
    void uploadTooLarge() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", big);
        ResponseEntity<?> r = controller.uploadSlip(file);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("uploadSlip: สำเร็จ → 200 + คืน path จาก Cloudinary")
    void uploadSuccess() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap()))
                .thenReturn(ObjectUtils.asMap("secure_url", "https://res.cloudinary.com/x.png"));
        ReflectionTestUtils.setField(controller, "cloudinary", cloudinary);

        MockMultipartFile file =
                new MockMultipartFile("file", "s.png", "image/png", new byte[] { 1, 2, 3 });
        ResponseEntity<?> r = controller.uploadSlip(file);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r).get("success")).isEqualTo(true);
        assertThat(body(r).get("path")).isEqualTo("https://res.cloudinary.com/x.png");
    }

    // ── verifySlip ──────────────────────────────────────────

    @Test
    @DisplayName("verifySlip: อ่านรูปไม่ได้ → success=false")
    void verifyUnreadable() {
        MockMultipartFile file =
                new MockMultipartFile("file", "s.png", "image/png", "not-an-image".getBytes());
        ResponseEntity<?> r = controller.verifySlip(file);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r).get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("verifySlip: รูปไม่มี QR → success=false")
    void verifyNoQr() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", blankPng());
        ResponseEntity<?> r = controller.verifySlip(file);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r).get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("verifySlip: QR มียอดเงิน (EMV tag 54) → success=true + amount")
    void verifyQrWithAmount() throws Exception {
        // tag 54, len 06, ค่า 100.00
        MockMultipartFile file =
                new MockMultipartFile("file", "s.png", "image/png", qrPng("5406100.00"));
        ResponseEntity<?> r = controller.verifySlip(file);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r).get("success")).isEqualTo(true);
        assertThat(body(r).get("amount")).isEqualTo(100.0);
    }
}