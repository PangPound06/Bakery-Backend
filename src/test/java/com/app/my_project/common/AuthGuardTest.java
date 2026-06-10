package com.app.my_project.common;

import com.app.my_project.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test AuthGuard — wrapper เช็ค auth/admin
 * - withAuth: ไม่มี user → 401, มี user → เรียก handler
 * - withAdmin: ไม่มี user → 401, ไม่ใช่ admin → 403, เป็น admin → เรียก handler
 */
@ExtendWith(MockitoExtension.class)
class AuthGuardTest {

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthGuard authGuard;

    private static final String AUTH = "Bearer token";

    private final Function<Long, ResponseEntity<Map<String, Object>>> handler =
            id -> ApiResponse.ok("handler-ran", Map.of("uid", id));

    @Test
    @DisplayName("requireAuth/isAdmin: delegate ไปยัง JwtService")
    void delegates() {
        when(jwtService.getUserIdFromHeader(AUTH)).thenReturn(7L);
        when(jwtService.isAdmin(AUTH)).thenReturn(true);

        assertThat(authGuard.requireAuth(AUTH)).isEqualTo(7L);
        assertThat(authGuard.isAdmin(AUTH)).isTrue();
    }

    @Test
    @DisplayName("withAuth: ไม่มี user (null) → 401 และไม่เรียก handler")
    void withAuthUnauthorized() {
        when(jwtService.getUserIdFromHeader(AUTH)).thenReturn(null);

        ResponseEntity<Map<String, Object>> res = authGuard.withAuth(AUTH, handler);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).containsEntry("success", false);
    }

    @Test
    @DisplayName("withAuth: มี user → เรียก handler และส่ง userId เข้าไป")
    void withAuthOk() {
        when(jwtService.getUserIdFromHeader(AUTH)).thenReturn(42L);

        ResponseEntity<Map<String, Object>> res = authGuard.withAuth(AUTH, handler);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("message", "handler-ran");
        assertThat(res.getBody()).containsEntry("uid", 42L);
    }

    @Test
    @DisplayName("withAdmin: ไม่มี user → 401")
    void withAdminUnauthorized() {
        when(jwtService.getUserIdFromHeader(AUTH)).thenReturn(null);

        ResponseEntity<Map<String, Object>> res = authGuard.withAdmin(AUTH, handler);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("withAdmin: มี user แต่ไม่ใช่ admin → 403")
    void withAdminForbidden() {
        when(jwtService.getUserIdFromHeader(AUTH)).thenReturn(5L);
        when(jwtService.isAdmin(AUTH)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = authGuard.withAdmin(AUTH, handler);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).containsEntry("success", false);
    }

    @Test
    @DisplayName("withAdmin: เป็น admin → เรียก handler")
    void withAdminOk() {
        when(jwtService.getUserIdFromHeader(AUTH)).thenReturn(1L);
        when(jwtService.isAdmin(AUTH)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = authGuard.withAdmin(AUTH, handler);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("message", "handler-ran");
        assertThat(res.getBody()).containsEntry("uid", 1L);
    }
}