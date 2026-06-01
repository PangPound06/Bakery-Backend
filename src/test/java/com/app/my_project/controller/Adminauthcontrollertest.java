package com.app.my_project.controller;

import com.app.my_project.common.AuthGuard;
import com.app.my_project.common.LoginRateLimiter;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test AdminAuthController
 *
 * Focus: business rules ที่ admin-specific
 *  - email ต้องลงท้าย @empbakery.com
 *  - status "active" เท่านั้นที่ login ได้
 *  - register ต้องผ่าน AuthGuard (admin only)
 *  - password ต้องมี ≥ 6 ตัวอักษร
 *  - ไม่มี duplicate email
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthControllerTest {

    @Mock private AdminRepository adminRepository;
    @Mock private UserRepository userRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private AuthGuard authGuard;
    @Mock private LoginRateLimiter loginRateLimiter;

    private AdminAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminAuthController(
                adminRepository, userRepository, passwordEncoder, jwtService, authGuard,
                loginRateLimiter);
    }

    private Map<String, String> loginRequest(String email, String password) {
        Map<String, String> req = new HashMap<>();
        req.put("email", email);
        req.put("password", password);
        return req;
    }

    private AdminEntity makeAdmin(String email, String status) {
        AdminEntity admin = new AdminEntity();
        admin.setId(1L);
        admin.setEmail(email);
        admin.setPassword("$2a$10$hashed");
        admin.setStatus(status);
        admin.setFullname("Admin User");
        admin.setRole("admin");
        return admin;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/admin/login")
    class LoginTests {

        @Test
        @DisplayName("Happy path: admin active login สำเร็จ → token role=ADMIN")
        void login_validActiveAdmin_succeeds() {
            AdminEntity admin = makeAdmin("admin@empbakery.com", "active");

            when(adminRepository.findByEmailIgnoreCase("admin@empbakery.com"))
                    .thenReturn(Optional.of(admin));
            when(passwordEncoder.matches("correct-pw", "$2a$10$hashed")).thenReturn(true);
            when(jwtService.generateToken(1L, JwtService.ROLE_ADMIN))
                    .thenReturn("admin-token");

            ResponseEntity<?> response = controller.loginAdmin(
                    loginRequest("admin@empbakery.com", "correct-pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsEntry("userType", "admin");
            assertThat(body).containsEntry("token", "admin-token");

            // ตรวจว่าได้ token role=ADMIN จริง
            verify(jwtService).generateToken(1L, JwtService.ROLE_ADMIN);
            verify(jwtService, never()).generateToken(anyLong(), eq(JwtService.ROLE_USER));
        }

        @Test
        @DisplayName("✅ CRITICAL: email ไม่ใช่ @empbakery.com → 400 ไม่ leak ข้อมูลว่ามี account หรือไม่")
        void login_nonCompanyEmail_returns400() {
            ResponseEntity<?> response = controller.loginAdmin(
                    loginRequest("hacker@gmail.com", "anything"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("@empbakery.com");

            // ✅ ตรวจว่าไม่ได้ query DB เลย — กัน timing attack / username enumeration
            verify(adminRepository, never()).findByEmailIgnoreCase(anyString());
        }

        @Test
        @DisplayName("✅ Admin บัญชี suspended → ห้าม login")
        void login_suspendedAdmin_rejects() {
            AdminEntity suspended = makeAdmin("admin@empbakery.com", "suspended");

            when(adminRepository.findByEmailIgnoreCase("admin@empbakery.com"))
                    .thenReturn(Optional.of(suspended));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true); // pw ถูก แต่ status ไม่ active

            ResponseEntity<?> response = controller.loginAdmin(
                    loginRequest("admin@empbakery.com", "correct-pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("ระงับ");

            // ตรวจว่าไม่ได้สร้าง token แม้ pw ถูก
            verify(jwtService, never()).generateToken(anyLong(), anyString());
        }

        @Test
        @DisplayName("Password ผิด → 400")
        void login_wrongPassword_returns400() {
            AdminEntity admin = makeAdmin("admin@empbakery.com", "active");

            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(admin));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            ResponseEntity<?> response = controller.loginAdmin(
                    loginRequest("admin@empbakery.com", "wrong"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(jwtService, never()).generateToken(anyLong(), anyString());
        }

        @Test
        @DisplayName("Admin ไม่มีในระบบ → 400")
        void login_adminNotFound_returns400() {
            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.loginAdmin(
                    loginRequest("nobody@empbakery.com", "pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("email/password ว่าง → 400")
        void login_emptyCredentials_returns400() {
            ResponseEntity<?> response = controller.loginAdmin(loginRequest("", ""));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(adminRepository, never()).findByEmailIgnoreCase(anyString());
        }

        @Test
        @DisplayName("Email case insensitive (ADMIN@EMPBAKERY.COM ก็ใช้ได้)")
        void login_uppercaseEmail_succeeds() {
            AdminEntity admin = makeAdmin("admin@empbakery.com", "active");

            // ✅ ระบบควร trim + ส่ง email ที่ original ไป (DB layer เป็นคน case-insensitive lookup)
            when(adminRepository.findByEmailIgnoreCase("ADMIN@EMPBAKERY.COM"))
                    .thenReturn(Optional.of(admin));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(jwtService.generateToken(anyLong(), anyString())).thenReturn("token");

            ResponseEntity<?> response = controller.loginAdmin(
                    loginRequest("ADMIN@EMPBAKERY.COM", "pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/admin/register — admin-only via AuthGuard")
    class RegisterTests {

        /**
         * เนื่องจาก registerAdmin ใช้ authGuard.withAdmin(...) → ต้อง mock guard
         * ให้ "ปล่อยผ่าน" โดยเรียก callback ที่ส่งเข้าไปจริงๆ
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void mockGuardAllowsAdmin(Long adminId) {
            when(authGuard.withAdmin(anyString(), any())).thenAnswer(inv -> {
                java.util.function.Function fn = inv.getArgument(1);
                return fn.apply(adminId);
            });
        }

        private Map<String, String> registerRequest(String email, String password, String fullname) {
            Map<String, String> req = new HashMap<>();
            req.put("email", email);
            req.put("password", password);
            req.put("fullname", fullname);
            return req;
        }

        @Test
        @DisplayName("Happy path: admin สร้าง admin ใหม่สำเร็จ")
        void register_validRequest_succeeds() {
            mockGuardAllowsAdmin(1L);
            when(adminRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            when(adminRepository.save(any())).thenAnswer(inv -> {
                AdminEntity a = inv.getArgument(0);
                a.setId(2L);
                return a;
            });

            ResponseEntity<?> response = controller.registerAdmin(
                    "Bearer admin-token",
                    registerRequest("new@empbakery.com", "secure123", "New Admin"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(passwordEncoder).encode("secure123"); // ✅ ต้อง hash password
            verify(adminRepository).save(any());
        }

        @Test
        @DisplayName("✅ Password < 6 ตัว → 400 (ไม่บันทึก)")
        void register_shortPassword_rejects() {
            mockGuardAllowsAdmin(1L);

            ResponseEntity<?> response = controller.registerAdmin(
                    "Bearer admin-token",
                    registerRequest("new@empbakery.com", "12345", "X")); // 5 ตัว

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("6");
            verify(adminRepository, never()).save(any());
        }

        @Test
        @DisplayName("Email ไม่ใช่ @empbakery.com → 400")
        void register_nonCompanyEmail_rejects() {
            mockGuardAllowsAdmin(1L);

            ResponseEntity<?> response = controller.registerAdmin(
                    "Bearer admin-token",
                    registerRequest("hacker@gmail.com", "secure123", "X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(adminRepository, never()).save(any());
        }

        @Test
        @DisplayName("✅ Email ซ้ำใน Admin → 400")
        void register_duplicateEmailInAdmin_rejects() {
            mockGuardAllowsAdmin(1L);
            when(adminRepository.existsByEmail("dup@empbakery.com")).thenReturn(true);

            ResponseEntity<?> response = controller.registerAdmin(
                    "Bearer admin-token",
                    registerRequest("dup@empbakery.com", "secure123", "X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("ถูกใช้งานแล้ว");
            verify(adminRepository, never()).save(any());
        }

        @Test
        @DisplayName("✅ Email ซ้ำใน User → 400 (กัน admin/user collision)")
        void register_emailAlreadyInUserTable_rejects() {
            mockGuardAllowsAdmin(1L);
            when(adminRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.existsByEmail("collision@empbakery.com")).thenReturn(true);

            ResponseEntity<?> response = controller.registerAdmin(
                    "Bearer admin-token",
                    registerRequest("collision@empbakery.com", "secure123", "X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(adminRepository, never()).save(any());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}