package com.app.my_project.controller;

import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.AdminRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/profile")
@CrossOrigin(origins = "https://bakery-frontend-next.vercel.app")
public class AdminProfileController {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    // ดึงข้อมูล Admin Profile
    @GetMapping("/{email}")
    public ResponseEntity<?> getAdminProfile(@PathVariable String email) {
        Map<String, Object> response = new HashMap<>();

        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบข้อมูล Admin");
            return ResponseEntity.badRequest().body(response);
        }

        AdminEntity admin = adminOpt.get();

        Map<String, Object> profile = new HashMap<>();
        profile.put("id", admin.getId());
        profile.put("email", admin.getEmail());
        profile.put("fullname", admin.getFullname());
        profile.put("phone", admin.getPhone());
        profile.put("address", admin.getAddress());
        profile.put("role", admin.getRole());
        profile.put("createdAt", admin.getCreatedAt());

        response.put("success", true);
        response.put("profile", profile);
        return ResponseEntity.ok(response);
    }

    // อัพเดท Admin Profile (ชื่อ, เบอร์โทร, ที่อยู่)
    @PutMapping("/{email}")
    public ResponseEntity<?> updateAdminProfile(
            @PathVariable String email,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบข้อมูล Admin");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            AdminEntity admin = adminOpt.get();

            if (request.containsKey("fullname")) {
                admin.setFullname(request.get("fullname"));
            }
            if (request.containsKey("phone")) {
                admin.setPhone(request.get("phone"));
            }
            if (request.containsKey("address")) {
                admin.setAddress(request.get("address"));
            }

            adminRepository.save(admin);

            response.put("success", true);
            response.put("message", "บันทึกข้อมูลสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // เปลี่ยนรหัสผ่าน Admin
    @PutMapping("/{email}/password")
    public ResponseEntity<?> changePassword(
            @PathVariable String email,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบข้อมูล Admin");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            AdminEntity admin = adminOpt.get();
            String currentPassword = request.get("currentPassword");
            String newPassword = request.get("newPassword");

            if (currentPassword == null || currentPassword.isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณากรอกรหัสผ่านปัจจุบัน");
                return ResponseEntity.badRequest().body(response);
            }

            if (!passwordEncoder.matches(currentPassword, admin.getPassword())) {
                response.put("success", false);
                response.put("message", "รหัสผ่านปัจจุบันไม่ถูกต้อง");
                return ResponseEntity.badRequest().body(response);
            }

            if (newPassword == null || newPassword.length() < 6) {
                response.put("success", false);
                response.put("message", "รหัสผ่านใหม่ต้องมีอย่างน้อย 6 ตัวอักษร");
                return ResponseEntity.badRequest().body(response);
            }

            admin.setPassword(passwordEncoder.encode(newPassword));
            adminRepository.save(admin);

            response.put("success", true);
            response.put("message", "เปลี่ยนรหัสผ่านสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.badRequest().body(response);
        }
    }
}