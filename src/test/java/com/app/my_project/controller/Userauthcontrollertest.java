package com.app.my_project.controller;

import com.app.my_project.common.AuthGuard;
import com.app.my_project.common.LoginRateLimiter;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.*;
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

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test UserAuthController.login() — security critical
 *
 * Test cases ครอบจุดที่เสี่ยง:
 *  - admin login → ต้องได้ role=ADMIN (เคยมี bug ใส่ผิดเป็น USER)
 *  - user login → role=USER ถูกต้อง
 *  - email/password ว่าง → reject
 *  - Google account ไม่มี password → ไม่ให้ login local
 *  - password ผิด → reject (ทั้ง admin และ user)
 *  - case insensitive email
 */
@ExtendWith(MockitoExtension.class)
class UserAuthControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private AdminRepository adminRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private DataSource dataSource;
    @Mock private JwtService jwtService;
    @Mock private AuthGuard authGuard;
    @Mock private LoginRateLimiter loginRateLimiter;

    private UserAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new UserAuthController(
                userRepository, adminRepository, passwordEncoder,
                userProfileRepository, orderRepository, orderItemRepository,
                favoriteRepository, dataSource, jwtService, authGuard,
                loginRateLimiter);
    }

    private Map<String, String> loginRequest(String email, String password) {
        Map<String, String> req = new HashMap<>();
        req.put("email", email);
        req.put("password", password);
        return req;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Input validation")
    class ValidationTests {

        @Test
        @DisplayName("email/password ว่าง → 400")
        void login_emptyCredentials_returns400() {
            ResponseEntity<?> response = controller.login(loginRequest("", ""));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("email = null → 400")
        void login_nullEmail_returns400() {
            ResponseEntity<?> response = controller.login(loginRequest(null, "password"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("password = null → 400")
        void login_nullPassword_returns400() {
            ResponseEntity<?> response = controller.login(loginRequest("a@b.com", null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Admin login flow")
    class AdminLoginTests {

        @Test
        @DisplayName("✅ CRITICAL: admin login ต้องได้ token role=ADMIN")
        void login_adminSuccess_generatesAdminToken() {
            AdminEntity admin = new AdminEntity();
            admin.setId(1L);
            admin.setEmail("admin@empbakery.com");
            admin.setPassword("$2a$10$hashed");

            when(adminRepository.findByEmailIgnoreCase("admin@empbakery.com"))
                    .thenReturn(Optional.of(admin));
            when(passwordEncoder.matches("correct-password", "$2a$10$hashed"))
                    .thenReturn(true);
            when(jwtService.generateToken(1L, JwtService.ROLE_ADMIN))
                    .thenReturn("admin-token-xxx");

            ResponseEntity<?> response = controller.login(
                    loginRequest("admin@empbakery.com", "correct-password"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsEntry("userType", "admin");
            assertThat(body).containsEntry("token", "admin-token-xxx");
            assertThat(body).containsEntry("redirectUrl", "/admin/dashboard");

            // ✅ ตรวจว่าไม่ generate token เป็น USER (กัน regression ของ bug เก่า)
            verify(jwtService, never()).generateToken(anyLong(), eq(JwtService.ROLE_USER));
        }

        @Test
        @DisplayName("admin email ถูก แต่ password ผิด → fallback ไปลอง user")
        void login_adminWrongPassword_fallsThrough() {
            AdminEntity admin = new AdminEntity();
            admin.setId(1L);
            admin.setPassword("$2a$10$hashed");

            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(admin));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
            // user ก็ไม่เจอ
            when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.login(loginRequest("x@y.com", "wrong"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(jwtService, never()).generateToken(anyLong(), anyString());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("User login flow")
    class UserLoginTests {

        @Test
        @DisplayName("user login ปกติ → token role=USER")
        void login_userSuccess_generatesUserToken() {
            UserEntity user = new UserEntity();
            user.setId(10L);
            user.setEmail("user@example.com");
            user.setPassword("$2a$10$hashed");
            user.setAuthProvider("local");

            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("user@example.com"))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pw123", "$2a$10$hashed")).thenReturn(true);
            when(jwtService.generateToken(10L, JwtService.ROLE_USER))
                    .thenReturn("user-token-xxx");

            ResponseEntity<?> response = controller.login(
                    loginRequest("user@example.com", "pw123"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsEntry("userType", "user");
            assertThat(body).containsEntry("token", "user-token-xxx");
        }

        @Test
        @DisplayName("✅ Google account (no password) → ต้องไม่ให้ login ผ่าน password")
        void login_googleAccount_rejectsPasswordLogin() {
            UserEntity googleUser = new UserEntity();
            googleUser.setEmail("googleuser@gmail.com");
            googleUser.setAuthProvider("google");
            googleUser.setPassword(null); // Google account ไม่มี password

            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(googleUser));

            ResponseEntity<?> response = controller.login(
                    loginRequest("googleuser@gmail.com", "anything"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("Google");
            verify(jwtService, never()).generateToken(anyLong(), anyString());
        }

        @Test
        @DisplayName("user password ผิด → 400")
        void login_userWrongPassword_returns400() {
            UserEntity user = new UserEntity();
            user.setPassword("$2a$10$hashed");
            user.setAuthProvider("local");

            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            ResponseEntity<?> response = controller.login(loginRequest("a@b.com", "wrong"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(jwtService, never()).generateToken(anyLong(), anyString());
        }

        @Test
        @DisplayName("ไม่พบ email ทั้งใน admin และ user → 400")
        void login_unknownEmail_returns400() {
            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.login(loginRequest("nobody@nowhere.com", "x"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("email มี whitespace นำหน้า/หลัง → trim ก่อน lookup")
        void login_emailWithWhitespace_trimsBeforeLookup() {
            when(adminRepository.findByEmailIgnoreCase("admin@empbakery.com"))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmailIgnoreCase("admin@empbakery.com"))
                    .thenReturn(Optional.empty());

            controller.login(loginRequest("  admin@empbakery.com  ", "pw"));

            // ตรวจว่า lookup ด้วย email ที่ trim แล้ว
            verify(adminRepository).findByEmailIgnoreCase("admin@empbakery.com");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}