package com.app.my_project.common;

import com.app.my_project.service.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

/**
 * Authorization guards — ลด boilerplate ของการเช็ค auth/admin ในทุก endpoint
 *
 * Before:
 *   Long userId = getUserIdFromToken(authHeader);
 *   if (userId == null) return ResponseEntity.status(401).body(Map.of(...));
 *   if (!isAdmin(userId)) return ResponseEntity.status(403).body(Map.of(...));
 *   // ... business logic
 *
 * After:
 *   return authGuard.requireAdmin(authHeader, userId -> {
 *     // ... business logic
 *   });
 */
@Component
public class AuthGuard {

    private final JwtService jwtService;

    public AuthGuard(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** ต้องล็อกอินก่อน — return user id ถ้า valid, null ถ้าไม่ */
    public Long requireAuth(String authHeader) {
        return jwtService.getUserIdFromHeader(authHeader);
    }

    /** ต้องเป็น admin */
    public boolean isAdmin(String authHeader) {
        return jwtService.isAdmin(authHeader);
    }

    /** Wrapper: ต้องล็อกอินก่อนทำต่อ */
    public ResponseEntity<Map<String, Object>> withAuth(
            String authHeader,
            Function<Long, ResponseEntity<Map<String, Object>>> handler) {
        Long userId = requireAuth(authHeader);
        if (userId == null) return ApiResponse.unauthorized();
        return handler.apply(userId);
    }

    /** Wrapper: ต้องเป็น admin เท่านั้น */
    public ResponseEntity<Map<String, Object>> withAdmin(
            String authHeader,
            Function<Long, ResponseEntity<Map<String, Object>>> handler) {
        Long userId = requireAuth(authHeader);
        if (userId == null) return ApiResponse.unauthorized();
        if (!isAdmin(authHeader)) return ApiResponse.forbidden();
        return handler.apply(userId);
    }
}
