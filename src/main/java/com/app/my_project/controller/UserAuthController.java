package com.app.my_project.controller;

import com.app.my_project.entity.UserEntity;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.repository.AdminRepository;

import com.app.my_project.repository.UserProfileRepository;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
@CrossOrigin(origins = "*")
public class UserAuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private UserProfileRepository userProfileRepository;

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
                response.put("redirectUrl", "/");

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
    public void googleCallback(@RequestParam("code") String code, HttpServletResponse httpResponse) throws Exception {
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
                            user.getAuthProvider() != null ? user.getAuthProvider() : "google", StandardCharsets.UTF_8);

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

    // GET ALL USERS
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll());
    }

    // GET USER BY ID
    @GetMapping("/user/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

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

    // ✅ UPDATE USER — ตรวจสอบรหัสผ่านเดิมก่อนเปลี่ยน
    @PutMapping("/user/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        Optional<UserEntity> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            UserEntity user = userOptional.get();

            // อัพเดทชื่อ
            if (request.containsKey("fullname")) {
                user.setFullname(request.get("fullname"));
            }

            // ✅ เปลี่ยนรหัสผ่าน — ต้องส่ง currentPassword มาด้วย
            if (request.containsKey("password") && !request.get("password").isEmpty()) {
                String currentPassword = request.get("currentPassword");
                String newPassword = request.get("password");

                // ตรวจสอบว่าส่งรหัสผ่านเดิมมาหรือไม่
                if (currentPassword == null || currentPassword.isEmpty()) {
                    response.put("success", false);
                    response.put("message", "กรุณากรอกรหัสผ่านปัจจุบัน");
                    return ResponseEntity.badRequest().body(response);
                }

                // ตรวจสอบว่าเป็น Google-only account หรือไม่
                if ("google".equals(user.getAuthProvider()) &&
                        (user.getPassword() == null || user.getPassword().isEmpty())) {
                    response.put("success", false);
                    response.put("message", "บัญชี Google ไม่สามารถเปลี่ยนรหัสผ่านได้");
                    return ResponseEntity.badRequest().body(response);
                }

                // ✅ ตรวจสอบรหัสผ่านเดิมว่าถูกต้องหรือไม่
                if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                    response.put("success", false);
                    response.put("message", "รหัสผ่านปัจจุบันไม่ถูกต้อง");
                    return ResponseEntity.badRequest().body(response);
                }

                // ตรวจสอบความยาวรหัสผ่านใหม่
                if (newPassword.length() < 6) {
                    response.put("success", false);
                    response.put("message", "รหัสผ่านใหม่ต้องมีอย่างน้อย 6 ตัวอักษร");
                    return ResponseEntity.badRequest().body(response);
                }

                // ✅ ผ่านทั้งหมด → เปลี่ยนรหัสผ่าน
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

    // DELETE USER
    @Transactional
    @DeleteMapping("/user/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        if (!userRepository.existsById(id)) {
            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // ✅ ลบ Profile ก่อน แล้วค่อยลบ User
            userProfileRepository.deleteByUserId(id);
            userRepository.deleteById(id);

            response.put("success", true);
            response.put("message", "ลบผู้ใช้สำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}