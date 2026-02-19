package com.app.my_project.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")
/* 
public class UploadFileController {

    // กำหนด path สำหรับเก็บรูปภาพใน static/uploads/images
    @Value("${upload.path:src/main/resources/static/uploads/images}")
    private String uploadPath;

    // URL สำหรับเข้าถึงรูปภาพ
    @Value("${upload.base-url:http://localhost:8080/uploads/images}")
    private String baseUrl;

    // Upload รูปภาพ POST /api/upload/image
    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // ตรวจสอบว่ามีไฟล์หรือไม่
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณาเลือกไฟล์");
                return ResponseEntity.badRequest().body(response);
            }

            // ตรวจสอบประเภทไฟล์
            String contentType = file.getContentType();
            if (contentType == null || !isAllowedImageType(contentType)) {
                response.put("success", false);
                response.put("message", "รองรับเฉพาะไฟล์รูปภาพ (JPG, PNG, GIF, WEBP)");
                return ResponseEntity.badRequest().body(response);
            }

            // ตรวจสอบขนาดไฟล์ (max 5MB)
            if (file.getSize() > 5 * 1024 * 1024) {
                response.put("success", false);
                response.put("message", "ขนาดไฟล์ต้องไม่เกิน 5MB");
                return ResponseEntity.badRequest().body(response);
            }

            // สร้าง directory ถ้ายังไม่มี
            Path uploadDir = Paths.get(uploadPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // ใช้ชื่อไฟล์เดิม
            String originalFilename = file.getOriginalFilename();

            // บันทึกไฟล์
            Path filePath = uploadDir.resolve(originalFilename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // สร้าง URL สำหรับเข้าถึงรูปภาพ
            String imageUrl = baseUrl + "/" + originalFilename;

            response.put("success", true);
            response.put("message", "อัพโหลดสำเร็จ");
            response.put("url", imageUrl);
            response.put("filename", originalFilename);
            response.put("originalFilename", originalFilename);
            response.put("size", file.getSize());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาดในการอัพโหลด: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    

    // ลบรูปภาพ DELETE /api/upload/image/{filename}
    @DeleteMapping("/image/{filename}")
    public ResponseEntity<?> deleteImage(@PathVariable String filename) {
        Map<String, Object> response = new HashMap<>();

        try {
            Path filePath = Paths.get(uploadPath).resolve(filename);

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                response.put("success", true);
                response.put("message", "ลบไฟล์สำเร็จ");
            } else {
                response.put("success", false);
                response.put("message", "ไม่พบไฟล์");
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ตรวจสอบประเภทไฟล์ที่อนุญาต
    private boolean isAllowedImageType(String contentType) {
        return contentType.equals("image/jpeg") ||
                contentType.equals("image/png") ||
                contentType.equals("image/gif") ||
                contentType.equals("image/webp");
    }

    
}
*/

public class UploadFileController {

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    private Cloudinary getCloudinary() {
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret
        ));
    }

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณาเลือกไฟล์");
                return ResponseEntity.badRequest().body(response);
            }

            String contentType = file.getContentType();
            if (contentType == null || !isAllowedImageType(contentType)) {
                response.put("success", false);
                response.put("message", "รองรับเฉพาะไฟล์รูปภาพ (JPG, PNG, GIF, WEBP)");
                return ResponseEntity.badRequest().body(response);
            }

            if (file.getSize() > 5 * 1024 * 1024) {
                response.put("success", false);
                response.put("message", "ขนาดไฟล์ต้องไม่เกิน 5MB");
                return ResponseEntity.badRequest().body(response);
            }

             byte[] bytes = file.getBytes();
        
        String uniqueFilename = UUID.randomUUID().toString(); // ← ชื่อไฟล์ใหม่ไม่มีภาษาไทย

        Map uploadResult = getCloudinary().uploader().upload(
                bytes,
                ObjectUtils.asMap(
                    "folder", "bakery",
                    "public_id", uniqueFilename  // ← ใช้ชื่อนี้แทน
                )
        );

        String imageUrl = (String) uploadResult.get("secure_url");
        String publicId = (String) uploadResult.get("public_id");

        response.put("success", true);
        response.put("message", "อัพโหลดสำเร็จ");
        response.put("url", imageUrl);
        response.put("filename", publicId);
        response.put("originalFilename", file.getOriginalFilename());
        response.put("size", file.getSize());

        return ResponseEntity.ok(response);

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาดในการอัพโหลด: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/image/{publicId}")
    public ResponseEntity<?> deleteImage(@PathVariable String publicId) {
        Map<String, Object> response = new HashMap<>();

        try {
            getCloudinary().uploader().destroy(publicId, ObjectUtils.emptyMap());
            response.put("success", true);
            response.put("message", "ลบไฟล์สำเร็จ");
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private boolean isAllowedImageType(String contentType) {
        return contentType.equals("image/jpeg") ||
                contentType.equals("image/png") ||
                contentType.equals("image/gif") ||
                contentType.equals("image/webp");
    }
}
