package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.common.AuthGuard;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.*;
import com.app.my_project.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User authentication & management
 *
 * แก้ไขจากเดิม:
 * - ลบ @CrossOrigin (ใช้ SecurityConfig จัดการ)
 * - ใช้ JwtService แทน inline JWT code
 * - login admin ผ่าน /api/auth/login ก็ต้องใส่ role="ADMIN" (เดิมใส่ผิดเป็น
 * USER!)
 * - ใช้ AuthGuard + ApiResponse → โค้ดสั้นลงเยอะ
 * - Constructor injection แทน field injection
 * - SLF4J logger แทน e.printStackTrace
 * - แยก helper findOrCreateGoogleUser ให้อ่านง่าย
 */
@RestController
@RequestMapping("/api/auth")
public class UserAuthController {

    private static final Logger log = LoggerFactory.getLogger(UserAuthController.class);

    private final UserRepository userRepository;
    private final AdminRepository adminRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final UserProfileRepository userProfileRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final FavoriteRepository favoriteRepository;
    private final DataSource dataSource;
    private final JwtService jwtService;
    private final AuthGuard authGuard;

    @Value("${google.client.id}")
    private String clientId;
    @Value("${google.client.secret}")
    private String clientSecret;
    @Value("${google.redirect.uri}")
    private String redirectUri;
    @Value("${frontend.url}")
    private String frontendUrl;

    public UserAuthController(UserRepository userRepository,
            AdminRepository adminRepository,
            BCryptPasswordEncoder passwordEncoder,
            UserProfileRepository userProfileRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            FavoriteRepository favoriteRepository,
            DataSource dataSource,
            JwtService jwtService,
            AuthGuard authGuard) {
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.userProfileRepository = userProfileRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.dataSource = dataSource;
        this.jwtService = jwtService;
        this.authGuard = authGuard;
    }

    // ─── Helper ─────────────────────────────────────────────────────────
    private Map<String, Object> buildUserData(UserEntity user) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("email", user.getEmail());
        data.put("fullname", user.getFullname() != null ? user.getFullname() : "");
        data.put("profileImage", user.getProfileImage() != null ? user.getProfileImage() : "");
        data.put("authProvider", user.getAuthProvider() != null ? user.getAuthProvider() : "local");
        return data;
    }

    private Map<String, Object> buildAdminData(AdminEntity admin) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", admin.getId());
        data.put("email", admin.getEmail());
        data.put("fullname", admin.getFullname() != null ? admin.getFullname() : "");
        data.put("role", admin.getRole() != null ? admin.getRole() : "admin");
        return data;
    }

    // LOGIN — รองรับทั้ง admin และ user
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            return ApiResponse.badRequest("กรุณากรอกอีเมลและรหัสผ่าน");
        }
        email = email.trim();

        // ── ลอง admin ก่อน ──
        Optional<AdminEntity> adminOpt = adminRepository.findByEmailIgnoreCase(email);
        if (adminOpt.isPresent() && passwordEncoder.matches(password, adminOpt.get().getPassword())) {
            AdminEntity admin = adminOpt.get();
            // ✅ ใส่ role="ADMIN" (เดิม bug: ใส่เป็น USER ทำให้ admin จัดการอะไรไม่ได้)
            String token = jwtService.generateToken(admin.getId(), JwtService.ROLE_ADMIN);

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("userType", "admin");
            data.put("redirectUrl", "/admin/dashboard");
            data.put("user", buildAdminData(admin));
            return ApiResponse.ok("เข้าสู่ระบบสำเร็จ", data);
        }

        // ── จากนั้นลอง user ──
        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();

            if ("google".equals(user.getAuthProvider())
                    && (user.getPassword() == null || user.getPassword().isEmpty())) {
                return ApiResponse.badRequest("บัญชีนี้ลงทะเบียนผ่าน Google กรุณาเข้าสู่ระบบด้วย Google");
            }

            if (user.getPassword() != null && passwordEncoder.matches(password, user.getPassword())) {
                String token = jwtService.generateToken(user.getId(), JwtService.ROLE_USER);

                Map<String, Object> data = new HashMap<>();
                data.put("token", token);
                data.put("userType", "user");
                data.put("redirectUrl", "/order-mode");
                data.put("user", buildUserData(user));
                return ApiResponse.ok("เข้าสู่ระบบสำเร็จ", data);
            }
        }

        return ApiResponse.badRequest("อีเมลหรือรหัสผ่านไม่ถูกต้อง");
    }

    // GOOGLE OAUTH
    @GetMapping("/google")
    public void googleLogin(HttpServletResponse httpResponse) throws Exception {
        String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=" + URLEncoder.encode("openid email profile", StandardCharsets.UTF_8)
                + "&access_type=offline&prompt=consent";
        httpResponse.sendRedirect(googleAuthUrl);
    }

    @GetMapping("/google/callback")
    public void googleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "error", required = false) String error,
            HttpServletResponse httpResponse) throws Exception {

        if (error != null || code == null) {
            httpResponse.sendRedirect(frontendUrl + "/login");
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            HttpClient client = HttpClient.newHttpClient();

            // 1. Exchange code → access_token
            String tokenBody = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                    + "&grant_type=authorization_code";

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                    .build();
            JsonNode tokenJson = mapper.readTree(
                    client.send(tokenRequest, HttpResponse.BodyHandlers.ofString()).body());

            if (tokenJson.has("error")) {
                httpResponse.sendRedirect(frontendUrl + "/login?error="
                        + URLEncoder.encode("Google authentication failed", StandardCharsets.UTF_8));
                return;
            }

            // 2. Get user info
            HttpRequest userInfoRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                    .header("Authorization", "Bearer " + tokenJson.get("access_token").asText())
                    .GET().build();
            JsonNode userInfo = mapper.readTree(
                    client.send(userInfoRequest, HttpResponse.BodyHandlers.ofString()).body());

            String googleId = userInfo.get("id").asText();
            String email = userInfo.get("email").asText();
            String name = userInfo.has("name") ? userInfo.get("name").asText() : "";
            String picture = userInfo.has("picture") ? userInfo.get("picture").asText() : "";

            // 3. Find or create user
            UserEntity user = findOrCreateGoogleUser(googleId, email, name, picture);

            // ✅ Google user → role=USER
            String jwtToken = jwtService.generateToken(user.getId(), JwtService.ROLE_USER);

            String redirectUrl = frontendUrl + "/auth/google/callback"
                    + "?token=" + URLEncoder.encode(jwtToken, StandardCharsets.UTF_8)
                    + "&userId=" + user.getId()
                    + "&email=" + URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8)
                    + "&fullname=" + URLEncoder.encode(
                            user.getFullname() != null ? user.getFullname() : "", StandardCharsets.UTF_8)
                    + "&profileImage=" + URLEncoder.encode(
                            user.getProfileImage() != null ? user.getProfileImage() : "", StandardCharsets.UTF_8)
                    + "&authProvider=" + URLEncoder.encode(
                            user.getAuthProvider() != null ? user.getAuthProvider() : "google",
                            StandardCharsets.UTF_8);

            httpResponse.sendRedirect(redirectUrl);

        } catch (Exception e) {
            log.error("Google OAuth callback failed", e);
            httpResponse.sendRedirect(frontendUrl + "/login?error="
                    + URLEncoder.encode("เกิดข้อผิดพลาดในการเข้าสู่ระบบด้วย Google", StandardCharsets.UTF_8));
        }
    }

    private UserEntity findOrCreateGoogleUser(String googleId, String email, String name, String picture) {
        // มี googleId แล้ว
        Optional<UserEntity> byGoogleId = userRepository.findByGoogleId(googleId);
        if (byGoogleId.isPresent()) {
            UserEntity u = byGoogleId.get();
            u.setFullname(name);
            u.setProfileImage(picture);
            return userRepository.save(u);
        }

        // มี email แล้ว (local) → link Google เข้าด้วยกัน
        Optional<UserEntity> byEmail = userRepository.findByEmailIgnoreCase(email);
        if (byEmail.isPresent()) {
            UserEntity u = byEmail.get();
            u.setGoogleId(googleId);
            u.setProfileImage(picture);
            if (u.getAuthProvider() == null || "local".equals(u.getAuthProvider())) {
                u.setAuthProvider("both");
            }
            return userRepository.save(u);
        }

        // สมาชิกใหม่
        UserEntity u = new UserEntity();
        u.setEmail(email);
        u.setFullname(name);
        u.setGoogleId(googleId);
        u.setProfileImage(picture);
        u.setAuthProvider("google");
        u.setPassword("");
        return userRepository.save(u);
    }

    // ═══════════════════════════════════════════════════════════════════
    // REGISTER USER
    // ═══════════════════════════════════════════════════════════════════
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");
        String fullname = request.get("fullname");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            return ApiResponse.badRequest("กรุณากรอกอีเมลและรหัสผ่าน");
        }
        if (password.length() < 6) {
            return ApiResponse.badRequest("รหัสผ่านต้องมีอย่างน้อย 6 ตัวอักษร");
        }

        email = email.trim().toLowerCase();
        if (email.endsWith("@empbakery.com")) {
            return ApiResponse.badRequest("ไม่สามารถใช้อีเมล @empbakery.com สมัครสมาชิกได้");
        }
        if (userRepository.existsByEmail(email) || adminRepository.existsByEmail(email)) {
            return ApiResponse.badRequest("อีเมลนี้ถูกใช้งานแล้ว");
        }

        try {
            UserEntity user = new UserEntity();
            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(password));
            user.setFullname(fullname);
            user.setAuthProvider("local");
            userRepository.save(user);
            return ApiResponse.ok("สมัครสมาชิกสำเร็จ");
        } catch (Exception e) {
            log.error("Failed to register user: {}", email, e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด กรุณาลองใหม่");
        }
    }

    // USER MANAGEMENT
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authGuard.requireAuth(authHeader) == null)
            return ApiResponse.unauthorized();
        if (!authGuard.isAdmin(authHeader))
            return ApiResponse.forbidden();

        List<Map<String, Object>> users = userRepository.findAll().stream().map(user -> {
            Map<String, Object> u = new HashMap<>();
            u.put("id", user.getId());
            u.put("email", user.getEmail());
            u.put("profileImage", user.getProfileImage());
            return u;
        }).toList();

        // ✅ ส่ง array โดยตรง compat กับ frontend
        return ResponseEntity.ok(users);
    }

    @GetMapping("/user/{id}")
    public ResponseEntity<Map<String, Object>> getUserById(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        return authGuard.withAuth(authHeader, tokenUserId -> {
            // เจ้าของ หรือ admin
            if (!tokenUserId.equals(id) && !authGuard.isAdmin(authHeader)) {
                return ApiResponse.forbidden();
            }

            Optional<UserEntity> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty())
                return ApiResponse.notFound("ไม่พบผู้ใช้");

            return ApiResponse.ok(Map.of("user", buildUserData(userOpt.get())));
        });
    }

    @PutMapping("/user/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        return authGuard.withAuth(authHeader, tokenUserId -> {
            // เฉพาะเจ้าของเท่านั้น
            if (!tokenUserId.equals(id))
                return ApiResponse.forbidden();

            Optional<UserEntity> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty())
                return ApiResponse.notFound("ไม่พบผู้ใช้");

            try {
                UserEntity user = userOpt.get();
                if (request.containsKey("fullname"))
                    user.setFullname(request.get("fullname"));

                if (request.containsKey("password") && !request.get("password").isEmpty()) {
                    String currentPassword = request.get("currentPassword");
                    String newPassword = request.get("password");

                    if (currentPassword == null || currentPassword.isEmpty()) {
                        return ApiResponse.badRequest("กรุณากรอกรหัสผ่านปัจจุบัน");
                    }
                    if ("google".equals(user.getAuthProvider())
                            && (user.getPassword() == null || user.getPassword().isEmpty())) {
                        return ApiResponse.badRequest("บัญชี Google ไม่สามารถเปลี่ยนรหัสผ่านได้");
                    }
                    if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                        return ApiResponse.badRequest("รหัสผ่านปัจจุบันไม่ถูกต้อง");
                    }
                    if (newPassword.length() < 6) {
                        return ApiResponse.badRequest("รหัสผ่านใหม่ต้องมีอย่างน้อย 6 ตัวอักษร");
                    }
                    user.setPassword(passwordEncoder.encode(newPassword));
                }

                userRepository.save(user);
                return ApiResponse.ok("อัพเดทข้อมูลสำเร็จ");
            } catch (Exception e) {
                log.error("Failed to update user id={}", id, e);
                return ApiResponse.serverError("เกิดข้อผิดพลาด");
            }
        });
    }

    @Transactional
    @DeleteMapping("/user/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        return authGuard.withAuth(authHeader, tokenUserId -> {
            if (!tokenUserId.equals(id) && !authGuard.isAdmin(authHeader)) {
                return ApiResponse.forbidden();
            }

            Optional<UserEntity> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty())
                return ApiResponse.notFound("ไม่พบผู้ใช้");

            try {
                UserEntity user = userOpt.get();
                String email = user.getEmail();

                // ลบ order items ทั้งหมดของ user (batch-friendly แทน N+1)
                List<OrderEntity> userOrders = orderRepository.findByEmailOrderByCreatedAtDesc(email);
                for (OrderEntity order : userOrders) {
                    orderItemRepository.deleteByOrderId(order.getId());
                }
                orderRepository.deleteByEmail(email);

                // tb_cart ไม่มี JPA repo → ต้องใช้ JDBC
                try (Connection conn = dataSource.getConnection();
                        PreparedStatement stmt = conn.prepareStatement("DELETE FROM tb_cart WHERE email = ?")) {
                    stmt.setString(1, email);
                    stmt.executeUpdate();
                }

                favoriteRepository.deleteByUserId(id);
                userProfileRepository.deleteByUserId(id);
                userRepository.deleteById(id);

                return ApiResponse.ok("ลบบัญชีสำเร็จ");
            } catch (Exception e) {
                log.error("Failed to delete user id={}", id, e);
                return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
            }
        });
    }
}
