package com.app.my_project.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Async
    public void sendOtpEmail(String toEmail, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("🔐 รหัส OTP สำหรับรีเซ็ตรหัสผ่าน - My Bakery");

            String htmlContent = buildOtpEmailTemplate(otp);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            System.out.println("✅ Sent OTP via Gmail to: " + toEmail);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send OTP email: " + e.getMessage());
        }
    }

    @Async
    public void sendPasswordChangedEmail(String toEmail) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("✅ รหัสผ่านถูกเปลี่ยนเรียบร้อยแล้ว - My Bakery");

            String htmlContent = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"font-family: Arial, sans-serif; padding: 20px;\"><div style=\"max-width: 500px; margin: 0 auto; background: #fff; border-radius: 10px; padding: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.1);\"><h1 style=\"color: #f59e0b; text-align: center;\">🧁 My Bakery</h1><div style=\"text-align: center; font-size: 48px; margin: 20px 0;\">✅</div><h2 style=\"text-align: center; color: #333;\">รหัสผ่านถูกเปลี่ยนแล้ว</h2><p style=\"color: #666; text-align: center;\">รหัสผ่านของบัญชีคุณได้ถูกเปลี่ยนเรียบร้อยแล้ว</p><p style=\"color: #ef4444; text-align: center; font-size: 12px;\">หากคุณไม่ได้ทำการเปลี่ยนรหัสผ่าน กรุณาติดต่อเราทันที</p></div></body></html>";

            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send Password Changed email: " + e.getMessage());
        }
    }

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