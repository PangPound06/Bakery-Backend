package com.app.my_project.controller;

import com.app.my_project.entity.UserEntity;
import com.app.my_project.entity.UserProfileEntity;
import com.app.my_project.repository.UserProfileRepository;
import com.app.my_project.repository.UserRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Test UserProfileController
 *
 * Focus:
 *  - JWT decoding (decode user ID จาก token จริงๆ)
 *  - Authorization: user แก้ไขได้แค่ของตัวเอง (path /{userId} = token userId)
 *  - getOrCreateProfile logic: profile ใหม่ถูกสร้างถ้ายังไม่มี
 *  - toSafeProfile: ไม่ leak id/userId ออก
 *  - sync fullname ระหว่าง User และ UserProfile
 *
 * Note: ไม่ test endpoint upload image เพราะ Cloudinary instantiate ใน
 *       method ตรงๆ (new Cloudinary()) mock ไม่ได้โดยไม่ refactor
 */
@ExtendWith(MockitoExtension.class)
class UserProfileControllerTest {

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private UserProfileController controller;

    private static final String JWT_SECRET = "test-secret-key-for-userprofile-test";

    @BeforeEach
    void setUp() {
        // Inject jwt secret และ Cloudinary config (กัน NPE ใน getCloudinary)
        ReflectionTestUtils.setField(controller, "jwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(controller, "cloudName", "test-cloud");
        ReflectionTestUtils.setField(controller, "apiKey", "test-key");
        ReflectionTestUtils.setField(controller, "apiSecret", "test-secret");
    }

    /** สร้าง valid JWT สำหรับ test (subject = userId) */
    private String tokenFor(Long userId) {
        return JWT.create()
                .withSubject(userId.toString())
                .sign(Algorithm.HMAC256(JWT_SECRET));
    }

    private String authHeader(Long userId) {
        return "Bearer " + tokenFor(userId);
    }

    private UserEntity makeUser(Long id, String email, String name) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(email);
        u.setFullname(name);
        return u;
    }

    private UserProfileEntity makeProfile(Long userId, String email) {
        UserProfileEntity p = new UserProfileEntity();
        p.setUserId(userId);
        p.setEmail(email);
        p.setFullname("Test User");
        p.setPhone("0812345678");
        p.setAddress("Bangkok");
        return p;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/profile/me — auth + safe response")
    class GetMyProfileTests {

        @Test
        @DisplayName("ไม่มี token → 401")
        void getMyProfile_noToken_returns401() {
            ResponseEntity<?> response = controller.getMyProfile(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("token ปลอม (sign คนละ secret) → 401")
        void getMyProfile_invalidToken_returns401() {
            String fakeToken = JWT.create()
                    .withSubject("1")
                    .sign(Algorithm.HMAC256("different-secret"));

            ResponseEntity<?> response = controller.getMyProfile("Bearer " + fakeToken);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Profile มีอยู่ → คืน safe profile (ไม่มี id/userId)")
        void getMyProfile_existingProfile_returnsSafe() {
            when(userProfileRepository.findByUserId(1L))
                    .thenReturn(Optional.of(makeProfile(1L, "test@example.com")));

            ResponseEntity<?> response = controller.getMyProfile(authHeader(1L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) castBody(response).get("profile");

            // ✅ ตรวจว่า leak ไม่ออก
            assertThat(profile).doesNotContainKey("id");
            assertThat(profile).doesNotContainKey("userId");
            assertThat(profile).doesNotContainKey("password");
            // ตรวจว่ามี field ที่ควรมี
            assertThat(profile).containsKeys("fullname", "email", "phone", "address");
        }

        @Test
        @DisplayName("Profile ยังไม่มี → สร้างใหม่จาก User")
        void getMyProfile_noProfile_createsFromUser() {
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(userRepository.findById(1L))
                    .thenReturn(Optional.of(makeUser(1L, "new@example.com", "New User")));
            when(userProfileRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResponseEntity<?> response = controller.getMyProfile(authHeader(1L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(userProfileRepository).save(any());
        }

        @Test
        @DisplayName("User ไม่มีในระบบ → 400")
        void getMyProfile_userNotExists_returns400() {
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getMyProfile(authHeader(1L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/profile/me — sync fullname กับ User")
    class UpdateMyProfileTests {

        @Test
        @DisplayName("✅ เปลี่ยน fullname → sync ทั้ง UserProfile และ User table")
        void updateMyProfile_fullnameChange_syncsToUserTable() {
            UserProfileEntity profile = makeProfile(1L, "test@example.com");
            UserEntity user = makeUser(1L, "test@example.com", "Old Name");

            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResponseEntity<?> response = controller.updateMyProfile(
                    authHeader(1L), Map.of("fullname", "New Name"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // ตรวจว่า UserProfile ถูก save พร้อม fullname ใหม่
            ArgumentCaptor<UserProfileEntity> profileCaptor =
                    ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(profileCaptor.capture());
            assertThat(profileCaptor.getValue().getFullname()).isEqualTo("New Name");

            // ตรวจว่า User ก็ถูก save ด้วย (sync)
            ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getFullname()).isEqualTo("New Name");
        }

        @Test
        @DisplayName("ส่ง phone/address อย่างเดียว → ไม่ touch user table")
        void updateMyProfile_phoneAddressOnly_noUserSync() {
            UserProfileEntity profile = makeProfile(1L, "test@example.com");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, String> req = new HashMap<>();
            req.put("phone", "0999999999");
            req.put("address", "New Address");

            controller.updateMyProfile(authHeader(1L), req);

            verify(userProfileRepository).save(any());
            // ไม่ได้แตะ User table เพราะไม่มี fullname change
            verify(userRepository, never()).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("ไม่มี token → 401")
        void updateMyProfile_noToken_returns401() {
            ResponseEntity<?> response = controller.updateMyProfile(null, Map.of());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/profile/{userId} — Authorization check")
    class GetProfileByIdTests {

        @Test
        @DisplayName("✅ CRITICAL: user 1 พยายามอ่าน profile ของ user 2 → 403")
        void getProfile_differentUser_returns403() {
            // Token = user 1 แต่ path = userId 2
            ResponseEntity<?> response = controller.getProfile(authHeader(1L), 2L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // ไม่ได้ไป query DB เลย
            verify(userRepository, never()).findById(anyLong());
            verify(userProfileRepository, never()).findByUserId(anyLong());
        }

        @Test
        @DisplayName("user 1 อ่าน profile ของตัวเอง → 200")
        void getProfile_sameUser_returns200() {
            when(userRepository.findById(1L))
                    .thenReturn(Optional.of(makeUser(1L, "self@example.com", "Self")));
            when(userProfileRepository.findByUserId(1L))
                    .thenReturn(Optional.of(makeProfile(1L, "self@example.com")));

            ResponseEntity<?> response = controller.getProfile(authHeader(1L), 1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ไม่มี token → 401")
        void getProfile_noToken_returns401() {
            ResponseEntity<?> response = controller.getProfile(null, 1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("User ไม่มีในระบบ → 400")
        void getProfile_userNotFound_returns400() {
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getProfile(authHeader(1L), 1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/profile/{userId} — Authorization check")
    class UpdateProfileByIdTests {

        @Test
        @DisplayName("✅ CRITICAL: user 1 พยายามแก้ profile ของ user 2 → 403")
        void updateProfile_differentUser_returns403() {
            ResponseEntity<?> response = controller.updateProfile(
                    authHeader(1L), 2L, Map.of("fullname", "Hacker"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // ไม่ได้ save อะไรเลย
            verify(userProfileRepository, never()).save(any());
            verify(userRepository, never()).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("user 1 แก้ profile ตัวเอง → 200")
        void updateProfile_sameUser_succeeds() {
            UserProfileEntity profile = makeProfile(1L, "test@example.com");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResponseEntity<?> response = controller.updateProfile(
                    authHeader(1L), 1L, Map.of("phone", "0999999999"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ไม่มี token → 401")
        void updateProfile_noToken_returns401() {
            ResponseEntity<?> response = controller.updateProfile(null, 1L, Map.of());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}