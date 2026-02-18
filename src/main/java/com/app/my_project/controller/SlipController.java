package com.app.my_project.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/slip")
@CrossOrigin(origins = "*")
public class SlipController {

    // โฟลเดอร์เก็บสลิป
    private static final String UPLOAD_DIR = "my-project/src/main/resources/static/uploads/slips/";

    // อัพโหลดสลิปการโอนเงิน
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

            // ตรวจสอบประเภทไฟล์ (รับเฉพาะรูปภาพ)
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

            // สร้างโฟลเดอร์ถ้ายังไม่มี
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            // สร้างชื่อไฟล์ใหม่ (ป้องกันชื่อซ้ำ)
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = "slip_" + UUID.randomUUID().toString() + extension;

            // บันทึกไฟล์
            Path filePath = Paths.get(UPLOAD_DIR + newFilename);
            Files.write(filePath, file.getBytes());

            System.out.println("✅ Slip uploaded: " + newFilename);

            response.put("success", true);
            response.put("message", "อัพโหลดสลิปสำเร็จ");
            response.put("filename", newFilename);
            response.put("path", "/uploads/slips/" + newFilename);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาดในการอัพโหลด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ดูสลิป (สำหรับ Admin ตรวจสอบ)
    @GetMapping("/{filename}")
    public ResponseEntity<?> getSlip(@PathVariable String filename) {
        try {
            Path filePath = Paths.get(UPLOAD_DIR + filename);
            
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] fileContent = Files.readAllBytes(filePath);
            String contentType = Files.probeContentType(filePath);

            return ResponseEntity.ok()
                    .header("Content-Type", contentType != null ? contentType : "image/jpeg")
                    .body(fileContent);

        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
