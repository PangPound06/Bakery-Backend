package com.app.my_project.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * อ่าน QR บนรูปสลิปโอนเงิน
 *
 * สลิปของธนาคารไทยทุกเจ้ามี QR สำหรับตรวจสอบรายการ — รูปที่ไม่มี QR จึงไม่ใช่สลิป
 * ข้อความใน QR ไม่ซ้ำกันในแต่ละรายการ จึงใช้กันสลิปใบเดียวแนบหลายคำสั่งซื้อได้ด้วย (ดู reference)
 *
 * ไม่ได้ยืนยันกับธนาคารว่ารายการมีจริง — แอดมินยังต้องเช็กยอดในแอปธนาคารก่อนกดยืนยันเสมอ
 */
@Service
public class SlipQrReader {

    public enum Verdict {
        OK,
        /** เปิดรูปไม่ได้ (ไฟล์เสีย หรือไม่ใช่ JPG/PNG) */
        UNREADABLE,
        /** ไม่พบ QR ในรูป */
        NO_QR,
        /** เป็น QR สำหรับจ่ายเงิน (เช่น QR พร้อมเพย์ของร้าน) ไม่ใช่สลิป */
        PAYMENT_QR
    }

    /**
     * @param reference ค่าที่ใช้แทนสลิปใบนี้ (SHA-256 ของข้อความใน QR) — null ถ้า verdict ไม่ใช่ OK
     */
    public record Result(Verdict verdict, String reference) {
        public boolean ok() {
            return verdict == Verdict.OK;
        }
    }

    /**
     * QR จ่ายเงินตามมาตรฐาน EMVCo ขึ้นต้นด้วย Payload Format Indicator "000201"
     * ส่วน QR ตรวจสอบสลิปของธนาคารไทยไม่ได้ขึ้นต้นแบบนี้
     */
    private static final String EMV_PAYMENT_PREFIX = "000201";

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());

    public Result read(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            return new Result(Verdict.UNREADABLE, null);
        }
        if (image == null)
            return new Result(Verdict.UNREADABLE, null);

        String text = decode(image);
        if (text == null || text.isBlank())
            return new Result(Verdict.NO_QR, null);
        if (text.startsWith(EMV_PAYMENT_PREFIX))
            return new Result(Verdict.PAYMENT_QR, null);
        return new Result(Verdict.OK, "slip:" + sha256(text));
    }

    /**
     * ลองสองวิธีแปลงภาพเป็นขาวดำ — HybridBinarizer เก่งกับภาพถ่าย
     * GlobalHistogramBinarizer เก่งกับภาพหน้าจอที่พื้นหลังเรียบ
     */
    private static String decode(BufferedImage image) {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        for (BinaryBitmap bitmap : new BinaryBitmap[] {
                new BinaryBitmap(new HybridBinarizer(source)),
                new BinaryBitmap(new GlobalHistogramBinarizer(source)) }) {
            try {
                return new QRCodeReader().decode(bitmap, HINTS).getText();
            } catch (ReaderException e) {
                // ไม่เจอ QR หรืออ่านไม่ครบ (ภาพเบลอ/ถูกตัด) — ลองวิธีถัดไป
            }
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM ไม่มี SHA-256", e);
        }
    }
}
