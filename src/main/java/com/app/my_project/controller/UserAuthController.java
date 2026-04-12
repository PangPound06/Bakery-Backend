package com.app.my_project.controller;

import com.app.my_project.entity.UserEntity;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.FavoriteRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import com.app.my_project.repository.UserProfileRepository;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class UserAuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private DataSource dataSource;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${google.client.id}")
    private String clientId;

    @Value("${google.client.secret}")
    private String clientSecret;

    @Value("${google.redirect.uri}")
    private String redirectUri;

    @Value("${frontend.url}")
    private String frontendUrl;

    private String generateToken(Long userId) {
        Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
        return JWT.create()
                .withSubject(userId.toString())
                .withIssuer("auth0")
                .sign(algorithm);
    }

    // ✅ Helper: decode JWT → ได้ userId
    private Long getUserIdFromToken(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer "))
                return null;
            String token = authHeader.replace("Bearer ", "");
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(jwtSecret)).build();
            return Long.parseLong(verifier.verify(token).getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    // ✅ Helper: ตรวจว่าเป็น Admin หรือไม่
    private boolean isAdmin(Long userId) {
        return adminRepository.findById(userId).isPresent();
    }

    // LOGIN
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "กรุณากรอกอีเมลและรหัสผ่าน");
            return ResponseEntity.badRequest().body(response);
        }

        email = email.trim();

        Optional<AdminEntity> adminOptional = adminRepository.findByEmailIgnoreCase(email);
        if (adminOptional.isPresent()) {
            AdminEntity admin = adminOptional.get();
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
                response.put("user", userData);

                return ResponseEntity.ok(response);
            }
        }

        Optional<UserEntity> userOptional = userRepository.findByEmailIgnoreCase(email);
        if (userOptional.isPresent()) {
            UserEntity user = userOptional.get();

            if ("google".equals(user.getAuthProvider())
                    && (user.getPassword() == null || user.getPassword().isEmpty())) {
                response.put("success", false);
                response.put("message", "บัญชีนี้ลงทะเบียนผ่าน Google กรุณาเข้าสู่ระบบด้วย Google");
                return ResponseEntity.badRequest().body(response);
            }

            if (user.getPassword() != null && passwordEncoder.matches(password, user.getPassword())) {
                String token = generateToken(user.getId());

                response.put("success", true);
                response.put("message", "เข้าสู่ระบบสำเร็จ");
                response.put("token", token);
                response.put("userType", "user");
                response.put("redirectUrl", "/order-mode");

                Map<String, Object> userData = new HashMap<>();
                userData.put("id", user.getId());
                userData.put("email", user.getEmail());
                userData.put("fullname", user.getFullname() != null ? user.getFullname() : "");
                userData.put("profileImage", user.getProfileImage() != null ? user.getProfileImage() : "");
                userData.put("authProvider", user.getAuthProvider() != null ? user.getAuthProvider() : "local");
                response.put("user", userData);

                return ResponseEntity.ok(response);
            }
        }

        response.put("success", false);
        response.put("message", "อีเมลหรือรหัสผ่านไม่ถูกต้อง");
        return ResponseEntity.badRequest().body(response);
    }

    // GOOGLE OAUTH - Step 1
    @GetMapping("/google")
    public void googleLogin(HttpServletResponse httpResponse) throws Exception {
        String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=" + URLEncoder.encode("openid email profile", StandardCharsets.UTF_8)
                + "&access_type=offline"
                + "&prompt=consent";

        httpResponse.sendRedirect(googleAuthUrl);
    }

    // GOOGLE OAUTH - Step 2
    @GetMapping("/google/callback")
    public void googleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "error", required = false) String error,
            HttpServletResponse httpResponse) throws Exception {

        if (error != null || code == null) {
            httpResponse.sendRedirect(frontendUrl + "/login");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newHttpClient();

        try {
            String tokenRequestBody = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                    + "&grant_type=authorization_code";

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenRequestBody))
                    .build();

            HttpResponse<String> tokenResponse = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode tokenJson = mapper.readTree(tokenResponse.body());

            if (tokenJson.has("error")) {
                httpResponse.sendRedirect(frontendUrl + "/login?error=" +
                        URLEncoder.encode("Google authentication failed", StandardCharsets.UTF_8));
                return;
            }

            String accessToken = tokenJson.get("access_token").asText();

            HttpRequest userInfoRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> userInfoResponse = client.send(userInfoRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode userInfo = mapper.readTree(userInfoResponse.body());

            String googleId = userInfo.get("id").asText();
            String email = userInfo.get("email").asText();
            String name = userInfo.has("name") ? userInfo.get("name").asText() : "";
            String picture = userInfo.has("picture") ? userInfo.get("picture").asText() : "";

            Optional<UserEntity> existingUser = userRepository.findByGoogleId(googleId);
            UserEntity user;

            if (existingUser.isPresent()) {
                user = existingUser.get();
                user.setFullname(name);
                user.setProfileImage(picture);
                userRepository.save(user);
            } else {
                Optional<UserEntity> existingByEmail = userRepository.findByEmailIgnoreCase(email);

                if (existingByEmail.isPresent()) {
                    user = existingByEmail.get();
                    user.setGoogleId(googleId);
                    user.setProfileImage(picture);
                    if (user.getAuthProvider() == null || "local".equals(user.getAuthProvider())) {
                        user.setAuthProvider("both");
                    }
                    userRepository.save(user);
                } else {
                    user = new UserEntity();
                    user.setEmail(email);
                    user.setFullname(name);
                    user.setGoogleId(googleId);
                    user.setProfileImage(picture);
                    user.setAuthProvider("google");
                    user.setPassword("");
                    userRepository.save(user);
                }
            }

            String jwtToken = generateToken(user.getId());

            String redirectUrl = frontendUrl + "/auth/google/callback"
                    + "?token=" + URLEncoder.encode(jwtToken, StandardCharsets.UTF_8)
                    + "&userId=" + user.getId()
                    + "&email=" + URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8)
                    + "&fullname="
                    + URLEncoder.encode(user.getFullname() != null ? user.getFullname() : "", StandardCharsets.UTF_8)
                    + "&profileImage="
                    + URLEncoder.encode(user.getProfileImage() != null ? user.getProfileImage() : "",
                            StandardCharsets.UTF_8)
                    + "&authProvider=" + URLEncoder.encode(
                            user.getAuthProvider() != null ? user.getAuthProvider() : "google",
                            StandardCharsets.UTF_8);

            httpResponse.sendRedirect(redirectUrl);

        } catch (Exception e) {
            e.printStackTrace();
            httpResponse.sendRedirect(frontendUrl + "/login?error=" +
                    URLEncoder.encode("เกิดข้อผิดพลาดในการเข้าสู่ระบบด้วย Google", StandardCharsets.UTF_8));
        }
    }

    // REGISTER USER
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String email = request.get("email");
        String password = request.get("password");
        String fullname = request.get("fullname");

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

        email = email.trim().toLowerCase();

        if (email.endsWith("@empbakery.com")) {
            response.put("success", false);
            response.put("message", "ไม่สามารถใช้อีเมล @empbakery.com สมัครสมาชิกได้");
            return ResponseEntity.badRequest().body(response);
        }

        if (userRepository.existsByEmail(email) || adminRepository.existsByEmail(email)) {
            response.put("success", false);
            response.put("message", "อีเมลนี้ถูกใช้งานแล้ว");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            UserEntity user = new UserEntity();
            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(password));
            user.setFullname(fullname);
            user.setAuthProvider("local");
            userRepository.save(user);

            response.put("success", true);
            response.put("message", "สมัครสมาชิกสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด กรุณาลองใหม่");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ✅ GET ALL USERS — เฉพาะ Admin เท่านั้น
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        if (!isAdmin(tokenUserId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึง"));

        return ResponseEntity.ok(
                userRepository.findAll().stream().map(user -> {
                    Map<String, Object> u = new HashMap<>();
                    u.put("id", user.getId());
                    u.put("email", user.getEmail());
                    u.put("profileImage", user.getProfileImage());
                    return u;
                }).toList());
    }

    // ✅ GET USER BY ID — ดูได้เฉพาะข้อมูลตัวเอง หรือ Admin
    @GetMapping("/user/{id}")
    public ResponseEntity<?> getUserById(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        // ✅ ตรวจว่าเป็นเจ้าของ หรือ Admin
        if (!tokenUserId.equals(id) && !isAdmin(tokenUserId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึงข้อมูลนี้"));

        Optional<UserEntity> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);
        }

        UserEntity user = userOptional.get();
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.getId());
        userData.put("email", user.getEmail());
        userData.put("fullname", user.getFullname());
        userData.put("profileImage", user.getProfileImage());
        userData.put("authProvider", user.getAuthProvider());

        response.put("success", true);
        response.put("user", userData);
        return ResponseEntity.ok(response);
    }

    // ✅ UPDATE USER — แก้ได้เฉพาะข้อมูลตัวเอง
    @PutMapping("/user/{id}")
    public ResponseEntity<?> updateUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        // ✅ ตรวจว่าเป็นเจ้าของเท่านั้น (Admin แก้ข้อมูลตัวเองไม่ได้ผ่าน endpoint นี้)
        if (!tokenUserId.equals(id))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์แก้ไขข้อมูลนี้"));

        Optional<UserEntity> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            UserEntity user = userOptional.get();

            if (request.containsKey("fullname")) {
                user.setFullname(request.get("fullname"));
            }

            if (request.containsKey("password") && !request.get("password").isEmpty()) {
                String currentPassword = request.get("currentPassword");
                String newPassword = request.get("password");

                if (currentPassword == null || currentPassword.isEmpty()) {
                    response.put("success", false);
                    response.put("message", "กรุณากรอกรหัสผ่านปัจจุบัน");
                    return ResponseEntity.badRequest().body(response);
                }

                if ("google".equals(user.getAuthProvider()) &&
                        (user.getPassword() == null || user.getPassword().isEmpty())) {
                    response.put("success", false);
                    response.put("message", "บัญชี Google ไม่สามารถเปลี่ยนรหัสผ่านได้");
                    return ResponseEntity.badRequest().body(response);
                }

                if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                    response.put("success", false);
                    response.put("message", "รหัสผ่านปัจจุบันไม่ถูกต้อง");
                    return ResponseEntity.badRequest().body(response);
                }

                if (newPassword.length() < 6) {
                    response.put("success", false);
                    response.put("message", "รหัสผ่านใหม่ต้องมีอย่างน้อย 6 ตัวอักษร");
                    return ResponseEntity.badRequest().body(response);
                }

                user.setPassword(passwordEncoder.encode(newPassword));
            }

            userRepository.save(user);

            response.put("success", true);
            response.put("message", "อัพเดทข้อมูลสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ✅ DELETE USER — ลบได้เฉพาะบัญชีตัวเอง หรือ Admin
    @Transactional
    @DeleteMapping("/user/{id}")
    public ResponseEntity<?> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        // ✅ ตรวจว่าเป็นเจ้าของ หรือ Admin
        if (!tokenUserId.equals(id) && !isAdmin(tokenUserId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์ลบข้อมูลนี้"));

        Optional<UserEntity> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            UserEntity user = userOpt.get();
            String email = user.getEmail();

            List<OrderEntity> userOrders = orderRepository.findByEmailOrderByCreatedAtDesc(email);
            for (OrderEntity order : userOrders) {
                orderItemRepository.deleteByOrderId(order.getId());
            }

            orderRepository.deleteByEmail(email);

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement("DELETE FROM tb_cart WHERE email = ?")) {
                stmt.setString(1, email);
                stmt.executeUpdate();
            }

            favoriteRepository.deleteByUserId(id);
            userProfileRepository.deleteByUserId(id);
            userRepository.deleteById(id);

            response.put("success", true);
            response.put("message", "ลบบัญชีสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}