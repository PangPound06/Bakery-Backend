package com.app.my_project.controller;

import com.app.my_project.service.ImageStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * อัปโหลด/ลบรูปสินค้า (ต้อง login / ลบได้เฉพาะแอดมิน — กำหนดใน SecurityConfig)
 *
 * สลิปโอนเงินไม่ผ่านที่นี่ — ต้องตรวจ QR ก่อนเก็บ ดู CheckoutController.submitTransfer
 */
@RestController
@RequestMapping("/api/upload/image")
public class UploadFileController {

    private static final Logger log = LoggerFactory.getLogger(UploadFileController.class);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final ImageStorageService imageStorage;

    public UploadFileController(ImageStorageService imageStorage) {
        this.imageStorage = imageStorage;
    }

    @PostMapping
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty())
            return badRequest("กรุณาเลือกไฟล์");
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType()))
            return badRequest("รองรับเฉพาะไฟล์รูปภาพ (JPG, PNG, GIF, WEBP)");
        if (file.getSize() > MAX_BYTES)
            return badRequest("ขนาดไฟล์ต้องไม่เกิน 5MB");

        try {
            ImageStorageService.StoredImage stored =
                    imageStorage.upload(file.getBytes(), ImageStorageService.FOLDER_PRODUCTS);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "อัพโหลดสำเร็จ");
            response.put("url", stored.url());
            response.put("filename", stored.publicId());
            response.put("originalFilename", file.getOriginalFilename());
            response.put("size", file.getSize());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("อัปโหลดรูปไม่สำเร็จ", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "เกิดข้อผิดพลาดในการอัพโหลด"));
        }
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<?> deleteImage(@PathVariable String publicId) {
        try {
            imageStorage.delete(publicId);
            return ResponseEntity.ok(Map.of("success", true, "message", "ลบไฟล์สำเร็จ"));
        } catch (IOException e) {
            log.error("ลบรูป {} ไม่สำเร็จ", publicId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "เกิดข้อผิดพลาดในการลบไฟล์"));
        }
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }
}
