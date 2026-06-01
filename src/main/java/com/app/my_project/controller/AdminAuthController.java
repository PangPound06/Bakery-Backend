package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.common.AuthGuard;
import com.app.my_project.common.AuthValidation;
import com.app.my_project.common.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Admin authentication & management
 *
 * แก้ไขจากเดิม:
 * - ลบ @CrossOrigin (ใช้ SecurityConfig จัดการ)
 * - ลบ helper getAdminIdFromToken/isAdmin (ย้ายไป JwtService)
 * - generateToken ใส่ role claim "ADMIN"
 * - ใช้ ApiResponse + AuthGuard → โค้ดสั้นลงครึ่งหนึ่ง
 * - ใช้ constructor injection
 * - เปลี่ยน e.printStackTrace → SLF4J logger
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminRepository adminRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthGuard authGuard;
    private final LoginRateLimiter loginRateLimiter;

    public AdminAuthController(AdminRepository adminRepository,
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthGuard authGuard,
            LoginRateLimiter loginRateLimiter) {
        this.adminRepository = adminRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authGuard = authGuard;
        this.loginRateLimiter = loginRateLimiter;
    }

    // ─── Helper ─────────────────────────────────────────────────────────
    private Map<String, Object> buildAdminData(AdminEntity admin) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", admin.getId());
        data.put("email", admin.getEmail());
        data.put("fullname", admin.getFullname() != null ? admin.getFullname() : "");
        data.put("role", admin.getRole() != null ? admin.getRole() : "admin");
        data.put("status", admin.getStatus());
        return data;
    }

    // LOGIN ADMIN
    @PostMapping("/login")
    public ResponseEntity<?> loginAdmin(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            return ApiResponse.badRequest("กรุณากรอกอีเมลและรหัสผ่าน");
        }
        email = email.trim();

        // ── validate รูปแบบ/ความยาว ──
        if (!AuthValidation.withinLength(email, AuthValidation.MAX_EMAIL_LEN)
                || !AuthValidation.withinLength(password, AuthValidation.MAX_PASSWORD_LEN)) {
            return ApiResponse.badRequest("อีเมลหรือรหัสผ่านยาวเกินกำหนด");
        }
        if (!AuthValidation.isValidEmailFormat(email)) {
            return ApiResponse.badRequest("รูปแบบอีเมลไม่ถูกต้อง");
        }

        if (!email.toLowerCase().endsWith("@empbakery.com")) {
            return ApiResponse.badRequest("กรุณาใช้อีเมลของบริษัท (@empbakery.com)");
        }

        // ── rate limit ต่อ IP (กัน brute-force) ──
        String clientKey = clientIp();
        if (loginRateLimiter.isBlocked(clientKey)) {
            long retry = loginRateLimiter.retryAfterSeconds(clientKey);
            return ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS,
                    "พยายามเข้าสู่ระบบบ่อยเกินไป กรุณาลองใหม่ใน " + retry + " วินาที");
        }

        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isEmpty() || !passwordEncoder.matches(password, adminOpt.get().getPassword())) {
            loginRateLimiter.recordFailure(clientKey);
            return ApiResponse.badRequest("อีเมลหรือรหัสผ่านไม่ถูกต้อง");
        }

        AdminEntity admin = adminOpt.get();
        if (!"active".equals(admin.getStatus())) {
            return ApiResponse.badRequest("บัญชีของคุณถูกระงับการใช้งาน");
        }

        loginRateLimiter.reset(clientKey);
        // ✅ สร้าง token พร้อม role="ADMIN"
        String token = jwtService.generateToken(admin.getId(), JwtService.ROLE_ADMIN);

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userType", "admin");
        data.put("redirectUrl", "/admin/dashboard");
        data.put("user", buildAdminData(admin));
        return ApiResponse.ok("เข้าสู่ระบบสำเร็จ", data);
    }

    /** ดึง client IP จาก request ปัจจุบัน (รองรับ proxy ผ่าน X-Forwarded-For) */
    private String clientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null)
            return "unknown";
        HttpServletRequest req = attrs.getRequest();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    // ADMIN MANAGEMENT (admin only)
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerAdmin(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> request) {
        return authGuard.withAdmin(authHeader, adminId -> {
            String email = request.get("email");
            String password = request.get("password");
            String fullname = request.get("fullname");
            String role = request.get("role");

            if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
                return ApiResponse.badRequest("กรุณากรอกอีเมลและรหัสผ่าน");
            }
            if (password.length() < 6) {
                return ApiResponse.badRequest("รหัสผ่านต้องมีอย่างน้อย 6 ตัวอักษร");
            }
            if (!email.toLowerCase().endsWith("@empbakery.com")) {
                return ApiResponse.badRequest("อีเมล Admin ต้องลงท้ายด้วย @empbakery.com");
            }

            String normalizedEmail = email.trim().toLowerCase();
            if (adminRepository.existsByEmail(normalizedEmail) || userRepository.existsByEmail(normalizedEmail)) {
                return ApiResponse.badRequest("อีเมลนี้ถูกใช้งานแล้ว");
            }

            try {
                AdminEntity admin = new AdminEntity();
                admin.setEmail(normalizedEmail);
                admin.setPassword(passwordEncoder.encode(password));
                admin.setFullname(fullname);
                admin.setRole(role != null && !role.isEmpty() ? role : "staff");
                admin.setStatus("active");
                adminRepository.save(admin);

                return ApiResponse.ok("เพิ่ม Admin สำเร็จ", Map.of("admin", buildAdminData(admin)));
            } catch (Exception e) {
                log.error("Failed to register admin: {}", normalizedEmail, e);
                return ApiResponse.serverError("เกิดข้อผิดพลาด กรุณาลองใหม่");
            }
        });
    }

    @GetMapping("/list")
    public ResponseEntity<?> getAllAdmins(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authGuard.requireAuth(authHeader) == null)
            return ApiResponse.unauthorized();
        if (!authGuard.isAdmin(authHeader))
            return ApiResponse.forbidden();

        // ✅ ส่ง array โดยตรง compat กับ frontend
        return ResponseEntity.ok(
                adminRepository.findAll().stream().map(this::buildAdminData).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getAdminById(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        return authGuard.withAdmin(authHeader, adminId -> {
            Optional<AdminEntity> adminOpt = adminRepository.findById(id);
            if (adminOpt.isEmpty())
                return ApiResponse.notFound("ไม่พบ Admin");
            return ApiResponse.ok(Map.of("admin", buildAdminData(adminOpt.get())));
        });
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateAdmin(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        return authGuard.withAdmin(authHeader, adminId -> {
            Optional<AdminEntity> adminOpt = adminRepository.findById(id);
            if (adminOpt.isEmpty())
                return ApiResponse.notFound("ไม่พบ Admin");

            try {
                AdminEntity admin = adminOpt.get();
                if (request.containsKey("fullname"))
                    admin.setFullname(request.get("fullname"));
                if (request.containsKey("role"))
                    admin.setRole(request.get("role"));
                if (request.containsKey("status"))
                    admin.setStatus(request.get("status"));
                if (request.containsKey("password") && !request.get("password").isEmpty()) {
                    admin.setPassword(passwordEncoder.encode(request.get("password")));
                }
                adminRepository.save(admin);

                return ApiResponse.ok("อัพเดทข้อมูลสำเร็จ", Map.of("admin", buildAdminData(admin)));
            } catch (Exception e) {
                log.error("Failed to update admin id={}", id, e);
                return ApiResponse.serverError("เกิดข้อผิดพลาด");
            }
        });
    }

    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<Map<String, Object>> toggleAdminStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        return authGuard.withAdmin(authHeader, adminId -> {
            Optional<AdminEntity> adminOpt = adminRepository.findById(id);
            if (adminOpt.isEmpty())
                return ApiResponse.notFound("ไม่พบ Admin");

            try {
                AdminEntity admin = adminOpt.get();
                String newStatus = "active".equals(admin.getStatus()) ? "inactive" : "active";
                admin.setStatus(newStatus);
                adminRepository.save(admin);

                return ApiResponse.ok("เปลี่ยนสถานะเป็น " + newStatus + " สำเร็จ",
                        Map.of("status", newStatus));
            } catch (Exception e) {
                log.error("Failed to toggle admin status id={}", id, e);
                return ApiResponse.serverError("เกิดข้อผิดพลาด");
            }
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteAdmin(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        return authGuard.withAdmin(authHeader, adminId -> {
            if (!adminRepository.existsById(id))
                return ApiResponse.notFound("ไม่พบ Admin");

            try {
                adminRepository.deleteById(id);
                return ApiResponse.ok("ลบ Admin สำเร็จ");
            } catch (Exception e) {
                log.error("Failed to delete admin id={}", id, e);
                return ApiResponse.serverError("เกิดข้อผิดพลาด");
            }
        });
    }
}