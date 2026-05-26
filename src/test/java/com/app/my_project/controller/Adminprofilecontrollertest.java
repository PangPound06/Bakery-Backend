package com.app.my_project.controller;

import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.AdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test AdminProfileController
 *
 * Focus: password change security
 *  - ต้อง verify current password ก่อน
 *  - new password ต้อง ≥ 6 ตัวอักษร
 *  - new password ต้องถูก hash ก่อนบันทึก
 *  - profile update partial (เปลี่ยนเฉพาะ field ที่ส่งมา)
 */
@ExtendWith(MockitoExtension.class)
class AdminProfileControllerTest {

    @Mock private AdminRepository adminRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminProfileController controller;

    private AdminEntity sampleAdmin;

    @BeforeEach
    void setUp() {
        sampleAdmin = new AdminEntity();
        sampleAdmin.setId(1L);
        sampleAdmin.setEmail("admin@empbakery.com");
        sampleAdmin.setFullname("Admin User");
        sampleAdmin.setPhone("0812345678");
        sampleAdmin.setAddress("123 Bangkok");
        sampleAdmin.setRole("admin");
        sampleAdmin.setPassword("$2a$10$existing-hashed");
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/admin/profile/{email}")
    class GetProfileTests {

        @Test
        @DisplayName("พบ admin → 200 + profile data")
        void getProfile_found_returns200() {
            when(adminRepository.findByEmailIgnoreCase("admin@empbakery.com"))
                    .thenReturn(Optional.of(sampleAdmin));

            ResponseEntity<?> response = controller.getAdminProfile("admin@empbakery.com");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) castBody(response).get("profile");
            assertThat(profile).containsEntry("email", "admin@empbakery.com");
            assertThat(profile).containsEntry("fullname", "Admin User");
            assertThat(profile).containsEntry("role", "admin");
            // ✅ ตรวจว่าไม่ leak password ออกไป
            assertThat(profile).doesNotContainKey("password");
        }

        @Test
        @DisplayName("ไม่พบ admin → 400")
        void getProfile_notFound_returns400() {
            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getAdminProfile("nobody@empbakery.com");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/admin/profile/{email} — partial update")
    class UpdateProfileTests {

        @Test
        @DisplayName("ส่ง fullname เท่านั้น → เปลี่ยน fullname, field อื่นคงเดิม")
        void updateProfile_partialFullname_keepsOthers() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));

            Map<String, String> req = Map.of("fullname", "New Name");

            ResponseEntity<?> response = controller.updateAdminProfile(
                    "admin@empbakery.com", req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
            verify(adminRepository).save(captor.capture());
            AdminEntity saved = captor.getValue();
            assertThat(saved.getFullname()).isEqualTo("New Name");
            // field อื่นคงเดิม
            assertThat(saved.getPhone()).isEqualTo("0812345678");
            assertThat(saved.getAddress()).isEqualTo("123 Bangkok");
        }

        @Test
        @DisplayName("ส่งทุก field → update ทั้งหมด")
        void updateProfile_allFields_updatesAll() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));

            Map<String, String> req = new HashMap<>();
            req.put("fullname", "Updated Name");
            req.put("phone", "0999999999");
            req.put("address", "New Address");

            controller.updateAdminProfile("admin@empbakery.com", req);

            ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
            verify(adminRepository).save(captor.capture());
            AdminEntity saved = captor.getValue();
            assertThat(saved.getFullname()).isEqualTo("Updated Name");
            assertThat(saved.getPhone()).isEqualTo("0999999999");
            assertThat(saved.getAddress()).isEqualTo("New Address");
        }

        @Test
        @DisplayName("ไม่พบ admin → 400")
        void updateProfile_notFound_returns400() {
            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.updateAdminProfile(
                    "nobody@empbakery.com", Map.of("fullname", "X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(adminRepository, never()).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/admin/profile/{email}/password — security critical")
    class ChangePasswordTests {

        private Map<String, String> passwordRequest(String current, String newPw) {
            Map<String, String> req = new HashMap<>();
            req.put("currentPassword", current);
            req.put("newPassword", newPw);
            return req;
        }

        @Test
        @DisplayName("Happy path: เปลี่ยนรหัสผ่านสำเร็จ + ใหม่ต้องถูก hash")
        void changePassword_validRequest_succeeds() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));
            when(passwordEncoder.matches("current-pw", "$2a$10$existing-hashed"))
                    .thenReturn(true);
            when(passwordEncoder.encode("new-secure-pw")).thenReturn("$2a$10$new-hashed");

            ResponseEntity<?> response = controller.changePassword(
                    "admin@empbakery.com",
                    passwordRequest("current-pw", "new-secure-pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // ตรวจว่า password ใหม่ถูก hash ก่อนบันทึก (ไม่ใช่ plain text)
            ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
            verify(adminRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword()).isEqualTo("$2a$10$new-hashed");
            verify(passwordEncoder).encode("new-secure-pw");
        }

        @Test
        @DisplayName("✅ Current password ผิด → 400 + ไม่บันทึก")
        void changePassword_wrongCurrent_rejects() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            ResponseEntity<?> response = controller.changePassword(
                    "admin@empbakery.com",
                    passwordRequest("wrong-pw", "new-secure-pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("ไม่ถูกต้อง");
            verify(adminRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("✅ New password < 6 ตัว → 400")
        void changePassword_newPasswordTooShort_rejects() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            ResponseEntity<?> response = controller.changePassword(
                    "admin@empbakery.com",
                    passwordRequest("current-pw", "12345")); // 5 ตัว

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("6");
            verify(adminRepository, never()).save(any());
        }

        @Test
        @DisplayName("Current password ว่าง → 400")
        void changePassword_emptyCurrent_rejects() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));

            ResponseEntity<?> response = controller.changePassword(
                    "admin@empbakery.com",
                    passwordRequest("", "new-secure-pw"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(passwordEncoder, never()).matches(anyString(), anyString());
        }

        @Test
        @DisplayName("New password ว่าง (null) → 400")
        void changePassword_nullNewPassword_rejects() {
            when(adminRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(sampleAdmin));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            ResponseEntity<?> response = controller.changePassword(
                    "admin@empbakery.com",
                    passwordRequest("current-pw", null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(adminRepository, never()).save(any());
        }

        @Test
        @DisplayName("ไม่พบ admin → 400")
        void changePassword_adminNotFound_rejects() {
            when(adminRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.changePassword(
                    "nobody@empbakery.com",
                    passwordRequest("any", "new-secure"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}