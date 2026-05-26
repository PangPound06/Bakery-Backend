package com.app.my_project.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test JwtService — security-critical
 *
 * ทดสอบ:
 * - generateToken: ออก JWT ถูกต้อง พร้อม claims
 * - decodeFromHeader: verify token + reject token ปลอม
 * - isAdmin / isUser: role-based check
 * - edge cases: null, malformed token, ผิด secret
 *
 * ใช้ ReflectionTestUtils.setField เพราะ jwtSecret มาจาก @Value
 * จะ inject ตอน test ตรงๆ ไม่ได้ ต้อง set ผ่าน reflection
 */
class JwtServiceTest {

    private JwtService jwtService;
    private static final String TEST_SECRET = "test-secret-key-for-junit-do-not-use-in-prod";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtSecret", TEST_SECRET);
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("generateToken()")
    class GenerateTokenTests {

        @Test
        @DisplayName("ออก token ที่มี structure ถูกต้อง (3 ส่วนคั่นด้วย .)")
        void generateToken_validInput_returnsThreePartJwt() {
            String token = jwtService.generateToken(1L, JwtService.ROLE_USER);

            assertThat(token).isNotNull().isNotBlank();
            assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
        }

        @Test
        @DisplayName("token มี subject = userId และ role claim ถูกต้อง")
        void generateToken_decodesBackWithCorrectClaims() {
            String token = jwtService.generateToken(42L, JwtService.ROLE_ADMIN);

            DecodedJWT decoded = jwtService.decodeFromHeader("Bearer " + token);

            assertThat(decoded).isNotNull();
            assertThat(decoded.getSubject()).isEqualTo("42");
            assertThat(decoded.getClaim("role").asString()).isEqualTo("ADMIN");
            assertThat(decoded.getIssuer()).isEqualTo(JwtService.ISSUER);
        }

        @Test
        @DisplayName("token ของ user แต่ละคนต้องไม่ซ้ำกัน")
        void generateToken_differentUsers_produceDifferentTokens() {
            String token1 = jwtService.generateToken(1L, JwtService.ROLE_USER);
            String token2 = jwtService.generateToken(2L, JwtService.ROLE_USER);

            assertThat(token1).isNotEqualTo(token2);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("decodeFromHeader()")
    class DecodeFromHeaderTests {

        @Test
        @DisplayName("คืน null ถ้า header เป็น null")
        void decodeFromHeader_nullHeader_returnsNull() {
            assertThat(jwtService.decodeFromHeader(null)).isNull();
        }

        @Test
        @DisplayName("คืน null ถ้า header ไม่ขึ้นต้นด้วย 'Bearer '")
        void decodeFromHeader_missingBearerPrefix_returnsNull() {
            String token = jwtService.generateToken(1L, JwtService.ROLE_USER);

            assertThat(jwtService.decodeFromHeader(token)).isNull();      // ไม่มี "Bearer "
            assertThat(jwtService.decodeFromHeader("Basic " + token)).isNull();
        }

        @Test
        @DisplayName("คืน null ถ้า token เป็น string มั่ว")
        void decodeFromHeader_malformedToken_returnsNull() {
            assertThat(jwtService.decodeFromHeader("Bearer not-a-real-jwt")).isNull();
            assertThat(jwtService.decodeFromHeader("Bearer ")).isNull();
        }

        @Test
        @DisplayName("คืน null ถ้า token ถูก sign ด้วย secret อื่น (token ปลอม)")
        void decodeFromHeader_signedWithDifferentSecret_returnsNull() {
            // สร้าง service อีกตัวที่ใช้ secret ต่าง
            JwtService attacker = new JwtService();
            ReflectionTestUtils.setField(attacker, "jwtSecret", "different-secret");

            String fakeToken = attacker.generateToken(999L, JwtService.ROLE_ADMIN);

            // verify ด้วย service ตัวจริง ต้อง reject
            assertThat(jwtService.decodeFromHeader("Bearer " + fakeToken)).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("isAdmin() / isUser()")
    class RoleCheckTests {

        @Test
        @DisplayName("token role=ADMIN → isAdmin=true, isUser=false")
        void isAdmin_adminToken_returnsTrue() {
            String token = jwtService.generateToken(1L, JwtService.ROLE_ADMIN);
            String header = "Bearer " + token;

            assertThat(jwtService.isAdmin(header)).isTrue();
            assertThat(jwtService.isUser(header)).isFalse();
        }

        @Test
        @DisplayName("token role=USER → isUser=true, isAdmin=false")
        void isUser_userToken_returnsTrue() {
            String token = jwtService.generateToken(1L, JwtService.ROLE_USER);
            String header = "Bearer " + token;

            assertThat(jwtService.isUser(header)).isTrue();
            assertThat(jwtService.isAdmin(header)).isFalse();
        }

        @Test
        @DisplayName("ไม่มี token → ทั้งสองคืน false (กันหลุดสิทธิ์)")
        void isAdmin_nullHeader_returnsFalse() {
            assertThat(jwtService.isAdmin(null)).isFalse();
            assertThat(jwtService.isUser(null)).isFalse();
        }

        @Test
        @DisplayName("token ปลอม → ทั้งสองคืน false")
        void isAdmin_fakeToken_returnsFalse() {
            assertThat(jwtService.isAdmin("Bearer fake.token.value")).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserIdFromHeader()")
    class GetUserIdTests {

        @Test
        @DisplayName("ดึง userId จาก token ที่ valid")
        void getUserIdFromHeader_validToken_returnsUserId() {
            String token = jwtService.generateToken(123L, JwtService.ROLE_USER);

            Long userId = jwtService.getUserIdFromHeader("Bearer " + token);

            assertThat(userId).isEqualTo(123L);
        }

        @Test
        @DisplayName("คืน null ถ้า token invalid")
        void getUserIdFromHeader_invalidToken_returnsNull() {
            assertThat(jwtService.getUserIdFromHeader("Bearer fake")).isNull();
            assertThat(jwtService.getUserIdFromHeader(null)).isNull();
        }
    }
}