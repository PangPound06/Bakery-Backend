package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.AdminRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Admin profile management
 *
 * แก้ไขจากเดิม:
 *  - ลบ @CrossOrigin
 *  - ใช้ ApiResponse
 *  - Constructor injection
 *  - SLF4J logger
 */
@RestController
@RequestMapping("/api/admin/profile")
public class AdminProfileController {

    private static final Logger log = LoggerFactory.getLogger(AdminProfileController.class);

    private final AdminRepository adminRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminProfileController(AdminRepository adminRepository, BCryptPasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/{email}")
    public ResponseEntity<Map<String, Object>> getAdminProfile(@PathVariable String email) {
        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) return ApiResponse.badRequest("ไม่พบข้อมูล Admin");

        AdminEntity admin = adminOpt.get();
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", admin.getId());
        profile.put("email", admin.getEmail());
        profile.put("fullname", admin.getFullname());
        profile.put("phone", admin.getPhone());
        profile.put("address", admin.getAddress());
        profile.put("role", admin.getRole());
        profile.put("createdAt", admin.getCreatedAt());

        return ApiResponse.ok(Map.of("profile", profile));
    }

    @PutMapping("/{email}")
    public ResponseEntity<Map<String, Object>> updateAdminProfile(
            @PathVariable String email,
            @RequestBody Map<String, String> request) {
        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) return ApiResponse.badRequest("ไม่พบข้อมูล Admin");

        try {
            AdminEntity admin = adminOpt.get();
            if (request.containsKey("fullname")) admin.setFullname(request.get("fullname"));
            if (request.containsKey("phone")) admin.setPhone(request.get("phone"));
            if (request.containsKey("address")) admin.setAddress(request.get("address"));
            adminRepository.save(admin);

            return ApiResponse.ok("บันทึกข้อมูลสำเร็จ");
        } catch (Exception e) {
            log.error("Failed to update admin profile for email={}", email, e);
            return ApiResponse.badRequest("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    @PutMapping("/{email}/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @PathVariable String email,
            @RequestBody Map<String, String> request) {
        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) return ApiResponse.badRequest("ไม่พบข้อมูล Admin");

        try {
            AdminEntity admin = adminOpt.get();
            String currentPassword = request.get("currentPassword");
            String newPassword = request.get("newPassword");

            if (currentPassword == null || currentPassword.isEmpty()) {
                return ApiResponse.badRequest("กรุณากรอกรหัสผ่านปัจจุบัน");
            }
            if (!passwordEncoder.matches(currentPassword, admin.getPassword())) {
                return ApiResponse.badRequest("รหัสผ่านปัจจุบันไม่ถูกต้อง");
            }
            if (newPassword == null || newPassword.length() < 6) {
                return ApiResponse.badRequest("รหัสผ่านใหม่ต้องมีอย่างน้อย 6 ตัวอักษร");
            }

            admin.setPassword(passwordEncoder.encode(newPassword));
            adminRepository.save(admin);
            return ApiResponse.ok("เปลี่ยนรหัสผ่านสำเร็จ");
        } catch (Exception e) {
            log.error("Failed to change password for admin email={}", email, e);
            return ApiResponse.badRequest("เกิดข้อผิดพลาด");
        }
    }
}
