package com.app.my_project.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/slip")
@CrossOrigin(origins = "https://poundbakery.vercel.app")
public class SlipController {

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    @Value("${anthropic.api-key}")
    private String anthropicApiKey;

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
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณาเลือกไฟล์");
                return ResponseEntity.badRequest().body(response);
            }

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                response.put("success", false);
                response.put("message", "กรุณาอัพโหลดไฟล์รูปภาพเท่านั้น");
                return ResponseEntity.badRequest().body(response);
            }

            if (file.getSize() > 10 * 1024 * 1024) {
                response.put("success", false);
                response.put("message", "ขนาดไฟล์ต้องไม่เกิน 10MB");
                return ResponseEntity.badRequest().body(response);
            }

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

    // ตรวจสอบยอดเงินในสลิปด้วย Claude API
    @PostMapping("/verify-amount")
    public ResponseEntity<?> verifySlipAmount(
            @RequestParam("file") MultipartFile file,
            @RequestParam("expectedAmount") double expectedAmount) {

        Map<String, Object> response = new HashMap<>();

        try {
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณาเลือกไฟล์");
                return ResponseEntity.badRequest().body(response);
            }

            // แปลงรูปเป็น base64
            byte[] fileBytes = file.getBytes();
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            String mediaType = file.getContentType() != null ? file.getContentType() : "image/jpeg";

            // สร้าง prompt สำหรับ Claude
            String prompt = String.format(
                "นี่คือสลิปโอนเงินจากแอปธนาคารไทย กรุณาอ่านยอดเงินที่โอนจากสลิปนี้\n\n" +
                "ยอดเงินที่คาดหวัง: %.2f บาท\n\n" +
                "กรุณาตอบในรูปแบบ JSON เท่านั้น ไม่ต้องมีข้อความอื่น:\n" +
                "{\"isSlip\": true/false, \"amount\": <ยอดเงินที่อ่านได้หรือ null>, \"amountMatches\": true/false, \"reason\": \"<เหตุผล>\"}\n\n" +
                "- isSlip: รูปนี้เป็นสลิปโอนเงินจากแอปธนาคารหรือไม่\n" +
                "- amount: ยอดเงินที่อ่านได้จากสลิป (ตัวเลขเท่านั้น ไม่มีหน่วย)\n" +
                "- amountMatches: ยอดเงินในสลิปตรงกับ %.2f บาทหรือไม่ (อนุญาตให้ต่างกันได้ไม่เกิน 1 บาท)\n" +
                "- reason: เหตุผลสั้นๆ เป็นภาษาไทย",
                expectedAmount, expectedAmount
            );

            // สร้าง request body สำหรับ Claude API
            ObjectMapper mapper = new ObjectMapper();
            String requestBody = mapper.writeValueAsString(Map.of(
                "model", "claude-opus-4-6",
                "max_tokens", 300,
                "messages", new Object[]{
                    Map.of(
                        "role", "user",
                        "content", new Object[]{
                            Map.of(
                                "type", "image",
                                "source", Map.of(
                                    "type", "base64",
                                    "media_type", mediaType,
                                    "data", base64Image
                                )
                            ),
                            Map.of(
                                "type", "text",
                                "text", prompt
                            )
                        }
                    )
                }
            ));

            // เรียก Claude API
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest claudeRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> claudeResponse = client.send(claudeRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode claudeJson = mapper.readTree(claudeResponse.body());

            System.out.println("Claude response: " + claudeResponse.body());

            // parse ผลลัพธ์จาก Claude
            String claudeText = claudeJson.get("content").get(0).get("text").asText().trim();

            // ลบ markdown code block ถ้ามี
            claudeText = claudeText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            JsonNode result = mapper.readTree(claudeText);

            boolean isSlip = result.has("isSlip") && result.get("isSlip").asBoolean();
            boolean amountMatches = result.has("amountMatches") && result.get("amountMatches").asBoolean();
            Double detectedAmount = result.has("amount") && !result.get("amount").isNull()
                ? result.get("amount").asDouble() : null;
            String reason = result.has("reason") ? result.get("reason").asText() : "";

            if (!isSlip) {
                response.put("success", false);
                response.put("valid", false);
                response.put("message", "ไม่ใช่สลิปโอนเงินจากแอปธนาคาร");
                response.put("reason", reason);
                return ResponseEntity.ok(response);
            }

            if (!amountMatches) {
                response.put("success", false);
                response.put("valid", false);
                response.put("detectedAmount", detectedAmount);
                response.put("expectedAmount", expectedAmount);
                response.put("message", String.format(
                    "ยอดเงินในสลิป (%.2f บาท) ไม่ตรงกับยอดที่ต้องชำระ (%.2f บาท)",
                    detectedAmount != null ? detectedAmount : 0, expectedAmount
                ));
                response.put("reason", reason);
                return ResponseEntity.ok(response);
            }

            response.put("success", true);
            response.put("valid", true);
            response.put("detectedAmount", detectedAmount);
            response.put("expectedAmount", expectedAmount);
            response.put("message", "สลิปถูกต้อง ยอดเงินตรงกัน");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("valid", false);
            response.put("message", "ไม่สามารถตรวจสอบสลิปได้: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}