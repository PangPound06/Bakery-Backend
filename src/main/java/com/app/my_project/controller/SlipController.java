package com.app.my_project.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

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
@CrossOrigin(origins = "https://bakery-frontend-next.vercel.app")
public class SlipController {

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
            "api_secret", apiSecret
        ));
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

            // ตรวจสอบขนาดไฟล์ (ไม่เกิน 5MB)
            if (file.getSize() > 5 * 1024 * 1024) {
                response.put("success", false);
                response.put("message", "ขนาดไฟล์ต้องไม่เกิน 5MB");
                return ResponseEntity.badRequest().body(response);
            }

            // อัพโหลดไป Cloudinary
            String publicId = "slips/slip_" + UUID.randomUUID().toString();

            @SuppressWarnings("unchecked")
            Map<String, Object> uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                "public_id", publicId,
                "folder", "bakery",
                "resource_type", "image"
            ));

            String secureUrl = (String) uploadResult.get("secure_url");

            System.out.println("✅ Slip uploaded to Cloudinary: " + secureUrl);

            response.put("success", true);
            response.put("message", "อัพโหลดสลิปสำเร็จ");
            response.put("path", secureUrl);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาดในการอัพโหลด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}