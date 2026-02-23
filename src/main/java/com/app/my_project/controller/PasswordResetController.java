package com.app.my_project.controller;

import com.app.my_project.entity.UserEntity;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.service.EmailService;
import com.app.my_project.service.OTPService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "https://bakery-frontend-next.vercel.app")
public class PasswordResetController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private OTPService otpService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

     // ขอรหัส OTP - ส่งไปยังอีเมล (ค้นหาทั้ง User และ Admin)
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");

        if (email == null || email.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "กรุณากรอกอีเมล");
            return ResponseEntity.badRequest().body(response);
        }

        email = email.trim();

        // ตรวจสอบว่ามีอีเมลนี้ในระบบหรือไม่ (ทั้ง User และ Admin)
        boolean foundInUser = userRepository.findByEmailIgnoreCase(email).isPresent();
        boolean foundInAdmin = adminRepository.findByEmailIgnoreCase(email).isPresent();

        if (!foundInUser && !foundInAdmin) {
            response.put("success", false);
            response.put("message", "ไม่พบอีเมลนี้ในระบบ");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // สร้าง OTP
            String otp = otpService.generateOtp(email.toLowerCase());

            // ส่ง OTP ไปยังอีเมล
            emailService.sendOtpEmail(email, otp);

            response.put("success", true);
            response.put("message", "ส่งรหัส OTP ไปยังอีเมลของคุณแล้ว");
            response.put("userType", foundInAdmin ? "admin" : "user");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "ไม่สามารถส่งอีเมลได้ กรุณาลองใหม่อีกครั้ง");
            return ResponseEntity.internalServerError().body(response);
        }
    }

     // ยืนยัน OTP
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");
        String otp = request.get("otp");

        if (email == null || otp == null) {
            response.put("success", false);
            response.put("message", "ข้อมูลไม่ครบถ้วน");
            return ResponseEntity.badRequest().body(response);
        }

        email = email.trim().toLowerCase();

        if (otpService.verifyOtp(email, otp)) {
            String resetToken = otpService.generateOtp(email);

            response.put("success", true);
            response.put("message", "ยืนยัน OTP สำเร็จ");
            response.put("resetToken", resetToken);
            return ResponseEntity.ok(response);
        } else {
            response.put("success", false);
            response.put("message", "รหัส OTP ไม่ถูกต้องหรือหมดอายุ");
            return ResponseEntity.badRequest().body(response);
        }
    }

     // รีเซ็ตรหัสผ่าน (รองรับทั้ง User และ Admin)
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");
        String resetToken = request.get("resetToken");
        String newPassword = request.get("newPassword");

        if (email == null || resetToken == null || newPassword == null) {
            response.put("success", false);
            response.put("message", "ข้อมูลไม่ครบถ้วน");
            return ResponseEntity.badRequest().body(response);
        }

        String emailLower = email.trim().toLowerCase();

        if (newPassword.length() < 6) {
            response.put("success", false);
            response.put("message", "รหัสผ่านต้องมีอย่างน้อย 6 ตัวอักษร");
            return ResponseEntity.badRequest().body(response);
        }

        if (!otpService.verifyOtp(emailLower, resetToken)) {
            response.put("success", false);
            response.put("message", "Token ไม่ถูกต้องหรือหมดอายุ กรุณาเริ่มใหม่");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // ===== ค้นหาใน Admin ก่อน =====
            Optional<AdminEntity> adminOptional = adminRepository.findByEmailIgnoreCase(email);
            if (adminOptional.isPresent()) {
                AdminEntity admin = adminOptional.get();
                admin.setPassword(passwordEncoder.encode(newPassword));
                adminRepository.save(admin);

                try {
                    emailService.sendPasswordChangedEmail(email);
                } catch (Exception e) {
                }

                response.put("success", true);
                response.put("message", "เปลี่ยนรหัสผ่านสำเร็จ");
                response.put("userType", "admin");
                return ResponseEntity.ok(response);
            }

            // ===== ค้นหาใน User =====
            Optional<UserEntity> userOptional = userRepository.findByEmailIgnoreCase(email);
            if (userOptional.isPresent()) {
                UserEntity user = userOptional.get();
                user.setPassword(passwordEncoder.encode(newPassword));
                userRepository.save(user);

                try {
                    emailService.sendPasswordChangedEmail(email);
                } catch (Exception e) {
                }

                response.put("success", true);
                response.put("message", "เปลี่ยนรหัสผ่านสำเร็จ");
                response.put("userType", "user");
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด กรุณาลองใหม่");
            return ResponseEntity.internalServerError().body(response);
        }
    }

     // ส่ง OTP ใหม่
    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");

        if (email == null || email.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "กรุณากรอกอีเมล");
            return ResponseEntity.badRequest().body(response);
        }

        email = email.trim();

        // ตรวจสอบว่ามีอีเมลนี้ในระบบหรือไม่
        boolean foundInUser = userRepository.findByEmailIgnoreCase(email).isPresent();
        boolean foundInAdmin = adminRepository.findByEmailIgnoreCase(email).isPresent();

        if (!foundInUser && !foundInAdmin) {
            response.put("success", false);
            response.put("message", "ไม่พบอีเมลนี้ในระบบ");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            String otp = otpService.generateOtp(email.toLowerCase());
            emailService.sendOtpEmail(email, otp);

            response.put("success", true);
            response.put("message", "ส่งรหัส OTP ใหม่เรียบร้อยแล้ว");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "ไม่สามารถส่งอีเมลได้ กรุณาลองใหม่อีกครั้ง");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}