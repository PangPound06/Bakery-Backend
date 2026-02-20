package com.app.my_project.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmailService {

    @Value("${resend.api.key}")
    private String resendApiKey;

    // ⚠️ ข้อควรระวัง: บัญชีฟรีของ Resend จะบังคับให้ใช้ชื่ออีเมลผู้ส่งเป็น onboarding@resend.dev เท่านั้น
    private final String fromEmail = "My Bakery <onboarding@resend.dev>";
    private final String resendApiUrl = "https://api.resend.com/emails";

    @Async
    public void sendOtpEmail(String toEmail, String otp) {
        String subject = "🔐 รหัส OTP สำหรับรีเซ็ตรหัสผ่าน - My Bakery";
        String htmlContent = buildOtpEmailTemplate(otp);
        sendEmailViaResend(toEmail, subject, htmlContent);
    }

    @Async
    public void sendPasswordChangedEmail(String toEmail) {
        String subject = "✅ รหัสผ่านถูกเปลี่ยนเรียบร้อยแล้ว - My Bakery";
        String htmlContent = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"font-family: Arial, sans-serif; padding: 20px;\"><div style=\"max-width: 500px; margin: 0 auto; background: #fff; border-radius: 10px; padding: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.1);\"><h1 style=\"color: #f59e0b; text-align: center;\">🧁 My Bakery</h1><div style=\"text-align: center; font-size: 48px; margin: 20px 0;\">✅</div><h2 style=\"text-align: center; color: #333;\">รหัสผ่านถูกเปลี่ยนแล้ว</h2><p style=\"color: #666; text-align: center;\">รหัสผ่านของบัญชีคุณได้ถูกเปลี่ยนเรียบร้อยแล้ว</p><p style=\"color: #ef4444; text-align: center; font-size: 12px;\">หากคุณไม่ได้ทำการเปลี่ยนรหัสผ่าน กรุณาติดต่อเราทันที</p></div></body></html>";
        sendEmailViaResend(toEmail, subject, htmlContent);
    }

    // ฟังก์ชันหลักสำหรับยิง API ไปหา Resend
    private void sendEmailViaResend(String toEmail, String subject, String htmlContent) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resendApiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("from", fromEmail);
            body.put("to", Arrays.asList(toEmail)); 
            body.put("subject", subject);
            body.put("html", htmlContent);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            
            // ยิงคำสั่ง POST ไปหา Resend
            ResponseEntity<String> response = restTemplate.postForEntity(resendApiUrl, request, String.class);
            
            System.out.println("✅ Sent email via Resend API to: " + toEmail + " | Status: " + response.getStatusCode());
        } catch (Exception e) {
            System.err.println("❌ Failed to send email via Resend API: " + e.getMessage());
        }
    }

    // สร้าง HTML Template สำหรับอีเมล OTP
    private String buildOtpEmailTemplate(String otp) {
        return "<!DOCTYPE html>"
            + "<html>"
            + "<head>"
            + "<meta charset=\"UTF-8\">"
            + "<style>"
            + "body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }"
            + ".container { max-width: 500px; margin: 0 auto; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.1); }"
            + ".header { background: linear-gradient(135deg, #f59e0b, #d97706); padding: 30px; text-align: center; }"
            + ".header h1 { color: #ffffff; margin: 0; font-size: 28px; }"
            + ".content { padding: 40px 30px; text-align: center; }"
            + ".otp-box { background: linear-gradient(135deg, #fef3c7, #fde68a); border: 2px dashed #f59e0b; border-radius: 12px; padding: 25px; margin: 25px 0; }"
            + ".otp-code { font-size: 36px; font-weight: bold; color: #92400e; letter-spacing: 8px; font-family: 'Courier New', monospace; }"
            + ".message { color: #6b7280; font-size: 14px; line-height: 1.6; margin: 20px 0; }"
            + ".warning { background-color: #fef2f2; border-left: 4px solid #ef4444; padding: 15px; margin: 20px 0; text-align: left; border-radius: 0 8px 8px 0; }"
            + ".warning p { color: #991b1b; margin: 0; font-size: 13px; }"
            + ".footer { background-color: #f9fafb; padding: 20px; text-align: center; border-top: 1px solid #e5e7eb; }"
            + ".footer p { color: #9ca3af; font-size: 12px; margin: 5px 0; }"
            + "</style>"
            + "</head>"
            + "<body>"
            + "<div class=\"container\">"
            + "<div class=\"header\">"
            + "<h1>🧁 My Bakery</h1>"
            + "</div>"
            + "<div class=\"content\">"
            + "<h2 style=\"color: #1f2937; margin-bottom: 10px;\">รีเซ็ตรหัสผ่าน</h2>"
            + "<p class=\"message\">คุณได้ขอรีเซ็ตรหัสผ่าน กรุณาใช้รหัส OTP ด้านล่างเพื่อยืนยันตัวตน</p>"
            + "<div class=\"otp-box\">"
            + "<p style=\"color: #92400e; margin: 0 0 10px 0; font-size: 14px;\">รหัส OTP ของคุณคือ</p>"
            + "<div class=\"otp-code\">" + otp + "</div>"
            + "</div>"
            + "<p class=\"message\">รหัสนี้จะหมดอายุใน <strong>5 นาที</strong></p>"
            + "<div class=\"warning\">"
            + "<p>⚠️ <strong>คำเตือน:</strong> อย่าแชร์รหัสนี้กับผู้อื่น</p>"
            + "</div>"
            + "</div>"
            + "<div class=\"footer\">"
            + "<p>หากคุณไม่ได้ขอรีเซ็ตรหัสผ่าน กรุณาเพิกเฉยอีเมลนี้</p>"
            + "<p>© 2026 My Bakery. All rights reserved.</p>"
            + "</div>"
            + "</div>"
            + "</body>"
            + "</html>";
    }
}