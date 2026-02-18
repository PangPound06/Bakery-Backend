package com.app.my_project.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

@Service
public class QRCodeService {

    // เบอร์ PromptPay ของคุณ (เบอร์โทร 10 หลัก หรือ เลขบัตรประชาชน 13 หลัก)
    private static final String PROMPTPAY_ID = "0931253748"; // ← ใส่เบอร์ของคุณ

    /**
     * สร้าง PromptPay Payload
     * ตาม EMVCo QR Code Specification
     */
    public String generatePromptPayPayload(double amount) {
        String promptPayId = PROMPTPAY_ID;
        
        // สร้าง Payload ตาม PromptPay Specification
        StringBuilder payload = new StringBuilder();
        
        // Payload Format Indicator
        payload.append("000201");
        
        // Point of Initiation Method (12 = Dynamic QR)
        payload.append("010212");
        
        // Merchant Account Information (PromptPay)
        String merchantInfo = buildMerchantInfo(promptPayId);
        payload.append("29").append(String.format("%02d", merchantInfo.length())).append(merchantInfo);
        
        // Transaction Currency (764 = THB)
        payload.append("5303764");
        
        // Transaction Amount
        if (amount > 0) {
            String amountStr = String.format("%.2f", amount);
            payload.append("54").append(String.format("%02d", amountStr.length())).append(amountStr);
        }
        
        // Country Code
        payload.append("5802TH");
        
        // CRC (จะคำนวณทีหลัง)
        payload.append("6304");
        
        // คำนวณ CRC16
        String crc = calculateCRC16(payload.toString());
        
        return payload.toString() + crc;
    }
    
    private String buildMerchantInfo(String promptPayId) {
        StringBuilder info = new StringBuilder();
        
        // Application ID (PromptPay)
        info.append("0016A000000677010111");
        
        if (promptPayId.length() == 10) {
            // ✅ เบอร์โทร 10 หลัก → แปลงเป็น 0066XXXXXXXXX (13 หลัก)
            // ตัด 0 นำหน้าออก แล้วเพิ่ม 0066
            String formattedPhone = "0066" + promptPayId.substring(1);
            info.append("01").append(String.format("%02d", formattedPhone.length())).append(formattedPhone);
        } else if (promptPayId.length() == 13) {
            // ✅ เลขบัตรประชาชน 13 หลัก → ใช้ตรงๆ
            info.append("02").append(String.format("%02d", promptPayId.length())).append(promptPayId);
        }
        
        return info.toString();
    }
    
    private String calculateCRC16(String data) {
        int crc = 0xFFFF;
        int polynomial = 0x1021;
        
        byte[] bytes = data.getBytes();
        for (byte b : bytes) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i) & 1) == 1);
                boolean c15 = ((crc >> 15 & 1) == 1);
                crc <<= 1;
                if (c15 ^ bit) {
                    crc ^= polynomial;
                }
            }
        }
        
        crc &= 0xFFFF;
        return String.format("%04X", crc);
    }

    /**
     * สร้าง QR Code เป็น Base64 Image
     */
    public String generateQRCodeBase64(double amount) throws WriterException, IOException {
        String payload = generatePromptPayPayload(amount);
        
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(payload, BarcodeFormat.QR_CODE, 300, 300);
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
        
        byte[] qrCodeBytes = outputStream.toByteArray();
        return Base64.getEncoder().encodeToString(qrCodeBytes);
    }
}