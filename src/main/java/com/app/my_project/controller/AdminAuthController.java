package com.app.my_project.controller;

import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "https://bakery-frontend-next.vercel.app")
public class AdminAuthController {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String generateToken(Long adminId) {
        Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
        return JWT.create()
                .withSubject(adminId.toString())
                .withIssuer("auth0")
                .sign(algorithm);
    }

    // LOGIN ADMIN - สำหรับ Admin โดยเฉพาะ
    @PostMapping("/login")
    public ResponseEntity<?> loginAdmin(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "กรุณากรอกอีเมลและรหัสผ่าน");
            return ResponseEntity.badRequest().body(response);
        }

        email = email.trim();

        // ตรวจสอบว่า email ต้องลงท้ายด้วย @empbakery.com
        if (!email.toLowerCase().endsWith("@empbakery.com")) {
            response.put("success", false);
            response.put("message", "กรุณาใช้อีเมลของบริษัท (@empbakery.com)");
            return ResponseEntity.badRequest().body(response);
        }

        Optional<AdminEntity> adminOptional = adminRepository.findByEmailIgnoreCase(email);
        if (adminOptional.isPresent()) {
            AdminEntity admin = adminOptional.get();

            // ตรวจสอบสถานะ
            if (!"active".equals(admin.getStatus())) {
                response.put("success", false);
                response.put("message", "บัญชีของคุณถูกระงับการใช้งาน");
                return ResponseEntity.badRequest().body(response);
            }

            if (passwordEncoder.matches(password, admin.getPassword())) {
                String token = generateToken(admin.getId());

                response.put("success", true);
                response.put("message", "เข้าสู่ระบบสำเร็จ");
                response.put("token", token);
                response.put("userType", "admin");
                response.put("redirectUrl", "/admin/dashboard");

                Map<String, Object> userData = new HashMap<>();
                userData.put("id", admin.getId());
                userData.put("email", admin.getEmail());
                userData.put("fullname", admin.getFullname() != null ? admin.getFullname() : "");
                userData.put("role", admin.getRole() != null ? admin.getRole() : "admin");
                userData.put("status", admin.getStatus());
                response.put("user", userData);

                return ResponseEntity.ok(response);
            }
        }

        response.put("success", false);
        response.put("message", "อีเมลหรือรหัสผ่านไม่ถูกต้อง");
        return ResponseEntity.badRequest().body(response);
    }

    // REGISTER ADMIN - เพิ่ม Admin ใหม่
    @PostMapping("/register")
    public ResponseEntity<?> registerAdmin(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");
        String password = request.get("password");
        String fullname = request.get("fullname");
        String role = request.get("role");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "กรุณากรอกอีเมลและรหัสผ่าน");
            return ResponseEntity.badRequest().body(response);
        }

        if (password.length() < 6) {
            response.put("success", false);
            response.put("message", "รหัสผ่านต้องมีอย่างน้อย 6 ตัวอักษร");
            return ResponseEntity.badRequest().body(response);
        }

        // ตรวจสอบว่า email ต้องลงท้ายด้วย @empbakery.com
        if (!email.toLowerCase().endsWith("@empbakery.com")) {
            response.put("success", false);
            response.put("message", "อีเมล Admin ต้องลงท้ายด้วย @empbakery.com");
            return ResponseEntity.badRequest().body(response);
        }

        email = email.trim().toLowerCase();

        // ตรวจสอบว่า email ซ้ำไหม
        if (adminRepository.existsByEmail(email) || userRepository.existsByEmail(email)) {
            response.put("success", false);
            response.put("message", "อีเมลนี้ถูกใช้งานแล้ว");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            AdminEntity admin = new AdminEntity();
            admin.setEmail(email);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setFullname(fullname);
            admin.setRole(role != null && !role.isEmpty() ? role : "staff");
            admin.setStatus("active");
            adminRepository.save(admin);

            response.put("success", true);
            response.put("message", "เพิ่ม Admin สำเร็จ");

            // ส่งข้อมูล admin กลับไป (ไม่รวม password)
            Map<String, Object> adminData = new HashMap<>();
            adminData.put("id", admin.getId());
            adminData.put("email", admin.getEmail());
            adminData.put("fullname", admin.getFullname());
            adminData.put("role", admin.getRole());
            adminData.put("status", admin.getStatus());
            response.put("admin", adminData);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด กรุณาลองใหม่");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // GET ALL ADMINS
    @GetMapping("/list")
    public ResponseEntity<?> getAllAdmins() {
        return ResponseEntity.ok(
                adminRepository.findAll().stream().map(admin -> {
                    Map<String, Object> a = new HashMap<>();
                    a.put("id", admin.getId());
                    a.put("email", admin.getEmail());
                    a.put("fullname", admin.getFullname());
                    a.put("role", admin.getRole());
                    a.put("status", admin.getStatus());
                    return a;
                }).toList());
    }

    // GET ADMIN BY ID
    @GetMapping("/{id}")
    public ResponseEntity<?> getAdminById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        Optional<AdminEntity> adminOptional = adminRepository.findById(id);
        if (adminOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบ Admin");
            return ResponseEntity.badRequest().body(response);
        }

        AdminEntity admin = adminOptional.get();
        Map<String, Object> adminData = new HashMap<>();
        adminData.put("id", admin.getId());
        adminData.put("email", admin.getEmail());
        adminData.put("fullname", admin.getFullname());
        adminData.put("role", admin.getRole());
        adminData.put("status", admin.getStatus());

        response.put("success", true);
        response.put("admin", adminData);
        return ResponseEntity.ok(response);
    }

    // UPDATE ADMIN
    @PutMapping("/{id}")
    public ResponseEntity<?> updateAdmin(@PathVariable Long id, @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        Optional<AdminEntity> adminOptional = adminRepository.findById(id);
        if (adminOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบ Admin");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            AdminEntity admin = adminOptional.get();

            if (request.containsKey("fullname")) {
                admin.setFullname(request.get("fullname"));
            }
            if (request.containsKey("role")) {
                admin.setRole(request.get("role"));
            }
            if (request.containsKey("status")) {
                admin.setStatus(request.get("status"));
            }
            if (request.containsKey("password") && !request.get("password").isEmpty()) {
                admin.setPassword(passwordEncoder.encode(request.get("password")));
            }

            adminRepository.save(admin);

            response.put("success", true);
            response.put("message", "อัพเดทข้อมูลสำเร็จ");

            Map<String, Object> adminData = new HashMap<>();
            adminData.put("id", admin.getId());
            adminData.put("email", admin.getEmail());
            adminData.put("fullname", admin.getFullname());
            adminData.put("role", admin.getRole());
            adminData.put("status", admin.getStatus());
            response.put("admin", adminData);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ============================================================
    // TOGGLE ADMIN STATUS (Active/Inactive)
    // ============================================================
    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<?> toggleAdminStatus(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        Optional<AdminEntity> adminOptional = adminRepository.findById(id);
        if (adminOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบ Admin");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            AdminEntity admin = adminOptional.get();
            String newStatus = "active".equals(admin.getStatus()) ? "inactive" : "active";
            admin.setStatus(newStatus);
            adminRepository.save(admin);

            response.put("success", true);
            response.put("message", "เปลี่ยนสถานะเป็น " + newStatus + " สำเร็จ");
            response.put("status", newStatus);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // DELETE ADMIN
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAdmin(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        if (!adminRepository.existsById(id)) {
            response.put("success", false);
            response.put("message", "ไม่พบ Admin");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            adminRepository.deleteById(id);
            response.put("success", true);
            response.put("message", "ลบ Admin สำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
