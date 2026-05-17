package com.app.my_project.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/slip")
public class SlipController {

    private static final Logger log = LoggerFactory.getLogger(SlipController.class);

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    private Cloudinary cloudinary;

    @PostConstruct
    public void init() {
        cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret));
    }

    // อัพโหลดสลิปไป Cloudinary
    @PostMapping("/upload")
    public ResponseEntity<?> uploadSlip(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // ตรวจสอบไฟล์
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณาเลือกไฟล์");
                return ResponseEntity.badRequest().body(response);
            }

            // ตรวจสอบประเภทไฟล์
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                response.put("success", false);
                response.put("message", "กรุณาอัพโหลดไฟล์รูปภาพเท่านั้น");
                return ResponseEntity.badRequest().body(response);
            }

            // ตรวจสอบขนาดไฟล์ (ไม่เกิน 10MB)
            if (file.getSize() > 10 * 1024 * 1024) {
                response.put("success", false);
                response.put("message", "ขนาดไฟล์ต้องไม่เกิน 10MB");
                return ResponseEntity.badRequest().body(response);
            }

            // อัพโหลดไป Cloudinary
            String publicId = "slips/slip_" + UUID.randomUUID().toString();

            @SuppressWarnings("unchecked")
            Map<String, Object> uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "public_id", publicId,
                    "folder", "bakery",
                    "resource_type", "image"));

            String secureUrl = (String) uploadResult.get("secure_url");

            System.out.println("✅ Slip uploaded to Cloudinary: " + secureUrl);

            response.put("success", true);
            response.put("message", "อัพโหลดสลิปสำเร็จ");
            response.put("path", secureUrl);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error", e);
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาดในการอัพโหลด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifySlip(
            @RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // อ่าน QR Code จากรูปสลิปด้วย ZXing (มีใน pom.xml แล้ว)
            javax.imageio.ImageIO.setUseCache(false);
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.getInputStream());

            if (image == null) {
                response.put("success", false);
                response.put("message", "ไม่สามารถอ่านรูปภาพได้");
                return ResponseEntity.ok(response);
            }

            com.google.zxing.LuminanceSource source = new com.google.zxing.client.j2se.BufferedImageLuminanceSource(
                    image);
            com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(source));

            // ตั้งค่าให้อ่าน QR Code ได้แม่นขึ้น
            java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                    java.util.Arrays.asList(com.google.zxing.BarcodeFormat.QR_CODE));

            com.google.zxing.Result qrResult = new com.google.zxing.MultiFormatReader().decode(bitmap, hints);
            String qrData = qrResult.getText();

            System.out.println("✅ QR Data from slip: " + qrData);

            // ดึงยอดเงินจาก QR Data
            // สลิปธนาคารไทยจะมี format: |amount| หรือตัวเลขทศนิยม 2 ตำแหน่ง
            Double amount = extractAmountFromQR(qrData);

            if (amount != null) {
                System.out.println("✅ Amount from QR: " + amount);
                response.put("success", true);
                response.put("amount", amount);
            } else {
                System.out.println("⚠️ Could not extract amount from QR data");
                response.put("success", false);
                response.put("message", "ไม่สามารถอ่านยอดเงินจาก QR Code ได้");
            }

            return ResponseEntity.ok(response);

        } catch (com.google.zxing.NotFoundException e) {
            response.put("success", false);
            response.put("message", "ไม่พบ QR Code ในสลิป");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error", e);
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    // ดึงยอดเงินจาก QR Code data ของสลิปธนาคารไทย
    private Double extractAmountFromQR(String qrData) {
        try {
            // Pattern 1: PromptPay slip format มี field ยอดเงิน
            // QR data มักมีรูปแบบ: ...Amount=100.00... หรือ ...54XX (tag 54 = amount ใน
            // EMV)

            // EMV QR Code: tag 54 = Transaction Amount
            java.util.regex.Pattern emvPattern = java.util.regex.Pattern.compile("54(\\d{2})(\\d+\\.?\\d*)");
            java.util.regex.Matcher emvMatcher = emvPattern.matcher(qrData);
            if (emvMatcher.find()) {
                String amountStr = emvMatcher.group(2);
                return Double.parseDouble(amountStr);
            }

            // Pattern 2: ตัวเลขทศนิยม 2 ตำแหน่งทั่วไป
            java.util.regex.Pattern amountPattern = java.util.regex.Pattern.compile("(\\d{1,7}\\.\\d{2})");
            java.util.regex.Matcher amountMatcher = amountPattern.matcher(qrData);

            java.util.List<Double> amounts = new java.util.ArrayList<>();
            while (amountMatcher.find()) {
                double val = Double.parseDouble(amountMatcher.group(1));
                if (val > 0 && val < 1000000) {
                    amounts.add(val);
                }
            }

            // คืนค่ายอดเงินที่พบ (ตัวแรกที่สมเหตุสมผล)
            if (!amounts.isEmpty()) {
                return amounts.get(0);
            }

            return null;
        } catch (Exception e) {
            System.out.println("⚠️ Error extracting amount: " + e.getMessage());
            return null;
        }
    }
}