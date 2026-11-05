package com.app.my_project.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * QR พร้อมเพย์ของร้าน (มาตรฐาน EMVCo / Thai QR Payment)
 *
 * ใช้สองที่: หน้า POS ของแอดมิน และการโอนพร้อมเพย์ที่แอดมินยืนยันเอง
 * เงินเข้าบัญชีพร้อมเพย์ของร้านตรง ไม่ผ่าน Stripe
 *
 * เลขพร้อมเพย์อ่านจาก promptpay.id (env PROMPTPAY_ID) — ตั้งผิดรูปแบบแอปจะไม่ยอมเริ่ม
 * ดีกว่าปล่อยให้ลูกค้าสแกน QR ที่โอนไม่ได้
 */
@Service
public class QRCodeService {

    private static final String PROMPTPAY_AID = "A000000677010111";
    private static final String CURRENCY_THB = "764";
    private static final String COUNTRY_TH = "TH";
    /** มีจำนวนเงิน = QR ใช้ครั้งเดียว (dynamic) / ไม่มี = QR ถาวร (static) */
    private static final String INITIATION_DYNAMIC = "12";
    private static final String INITIATION_STATIC = "11";
    private static final int QR_SIZE_PX = 300;

    private final String promptPayId;
    private final String accountName;

    public QRCodeService(
            @Value("${promptpay.id}") String promptPayId,
            @Value("${promptpay.account-name:}") String accountName) {
        this.promptPayId = normalizeId(promptPayId);
        this.accountName = accountName == null ? "" : accountName.trim();
    }

    // ═════════════════════════════════════════════════════════════════
    // PAYLOAD
    // ═════════════════════════════════════════════════════════════════

    /**
     * ข้อความใน QR ตามมาตรฐาน EMVCo
     *
     * @param amount ยอดเงินบาท — 0 หรือน้อยกว่า = QR ไม่ระบุยอด ให้ผู้โอนกรอกเอง
     */
    public String generatePromptPayPayload(double amount) {
        boolean hasAmount = amount > 0;

        StringBuilder payload = new StringBuilder()
                .append(tlv("00", "01"))
                .append(tlv("01", hasAmount ? INITIATION_DYNAMIC : INITIATION_STATIC))
                .append(tlv("29", merchantAccount()))
                .append(tlv("53", CURRENCY_THB));
        if (hasAmount) {
            payload.append(tlv("54", formatAmount(amount)));
        }
        payload.append(tlv("58", COUNTRY_TH))
                .append("6304"); // CRC คำนวณรวมแท็กและความยาวของตัวเองด้วย

        return payload + crc16(payload.toString());
    }

    /** รูป PNG ของ QR เป็น Base64 (ไม่มี prefix data:) */
    public String generateQRCodeBase64(double amount) throws WriterException, IOException {
        BitMatrix matrix = new QRCodeWriter().encode(
                generatePromptPayPayload(amount), BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }

    // ═════════════════════════════════════════════════════════════════
    // DISPLAY
    // ═════════════════════════════════════════════════════════════════

    /** เลขพร้อมเพย์แบบซ่อนบางส่วน ให้ลูกค้าเทียบกับที่แอปธนาคารแสดงได้ เช่น xxxxxx3748 */
    public String maskedPromptPayId() {
        int visible = 4;
        return "x".repeat(promptPayId.length() - visible) + promptPayId.substring(promptPayId.length() - visible);
    }

    /** ชื่อบัญชีที่ลูกค้าจะเห็นในแอปธนาคาร (ว่างได้) */
    public String accountName() {
        return accountName;
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /** แท็กย่อย 01 = เบอร์มือถือ, 02 = เลขบัตรประชาชน/ผู้เสียภาษี, 03 = e-Wallet */
    private String merchantAccount() {
        String account = switch (promptPayId.length()) {
            case 10 -> tlv("01", "0066" + promptPayId.substring(1));
            case 13 -> tlv("02", promptPayId);
            default -> tlv("03", promptPayId);
        };
        return tlv("00", PROMPTPAY_AID) + account;
    }

    private static String tlv(String tag, String value) {
        return tag + String.format(Locale.ROOT, "%02d", value.length()) + value;
    }

    /** ทศนิยม 2 ตำแหน่งเสมอ ไม่ขึ้นกับ locale ของเครื่อง (บางภาษาใช้ , แทน .) */
    private static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021) ตามที่ EMVCo กำหนด */
    private static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return String.format(Locale.ROOT, "%04X", crc & 0xFFFF);
    }

    /**
     * รับเลขที่มีขีด/ช่องว่างได้ แต่ต้องเป็นตัวเลข 10 (มือถือ), 13 (บัตรประชาชน) หรือ 15 หลัก (e-Wallet)
     */
    static String normalizeId(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        boolean validLength = digits.length() == 10 || digits.length() == 13 || digits.length() == 15;
        if (!digits.matches("\\d+") || !validLength || (digits.length() == 10 && !digits.startsWith("0"))) {
            throw new IllegalStateException(
                    "promptpay.id ต้องเป็นเบอร์มือถือ 10 หลัก เลขบัตรประชาชน 13 หลัก หรือ e-Wallet 15 หลัก");
        }
        return digits;
    }
}
