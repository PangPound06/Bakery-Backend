package com.app.my_project.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * รวม JWT logic ไว้ที่เดียว — ก่อนหน้านี้กระจายอยู่ใน 8 controllers
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String AUTHORITY_USER = "ROLE_USER";
    public static final String AUTHORITY_ADMIN = "ROLE_ADMIN";
    public static final String ISSUER = "auth0";

    @Value("${jwt.secret}")
    private String jwtSecret;

    /** สร้าง JWT พร้อม role claim */
    public String generateToken(Long userId, String role) {
        return JWT.create()
                .withSubject(userId.toString())
                .withIssuer(ISSUER)
                .withClaim("role", role)
                .sign(Algorithm.HMAC256(jwtSecret));
    }

    /** Verify และ decode token จาก Authorization header */
    public DecodedJWT decodeFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            return JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(ISSUER)
                    .build()
                    .verify(token);
        } catch (Exception e) {
            log.debug("JWT verification failed: {}", e.getMessage());
            return null;
        }
    }

    /** ดึง userId จาก Authorization header */
    public Long getUserIdFromHeader(String authHeader) {
        DecodedJWT decoded = decodeFromHeader(authHeader);
        if (decoded == null) return null;
        try {
            return Long.parseLong(decoded.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ดึง role จาก JWT claim */
    public String getRoleFromHeader(String authHeader) {
        DecodedJWT decoded = decodeFromHeader(authHeader);
        if (decoded == null) return null;
        return decoded.getClaim("role").asString();
    }

    /** ตรวจว่าเป็น admin จาก token */
    public boolean isAdmin(String authHeader) {
        return ROLE_ADMIN.equals(getRoleFromHeader(authHeader));
    }

    /** ตรวจว่าเป็น user (ไม่ใช่ admin) */
    public boolean isUser(String authHeader) {
        return ROLE_USER.equals(getRoleFromHeader(authHeader));
    }
}
