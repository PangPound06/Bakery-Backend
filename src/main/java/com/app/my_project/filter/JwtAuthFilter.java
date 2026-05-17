package com.app.my_project.filter;

import com.app.my_project.entity.AdminEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * JWT authentication filter
 *
 * เปลี่ยนแปลงจากเดิม:
 *  1) อ่าน "role" claim จาก JWT (token ใหม่จะมี ADMIN/USER)
 *  2) Set authority (ROLE_ADMIN/ROLE_USER) เข้า SecurityContext
 *     - ทำให้ใช้ @PreAuthorize("hasRole('ADMIN')") ได้
 *     - แก้ปัญหา 403 Forbidden ในหน้า admin
 *  3) Fallback: ถ้า token เก่าไม่มี role claim ก็ตรวจจาก DB
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String ISSUER = "auth0";

    private final String jwtSecret;
    private final UserRepository userRepository;
    private final AdminRepository adminRepository;

    public JwtAuthFilter(String jwtSecret, UserRepository userRepository, AdminRepository adminRepository) {
        this.jwtSecret = jwtSecret;
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String token = header.substring(7);
                DecodedJWT decoded = JWT.require(Algorithm.HMAC256(jwtSecret))
                        .withIssuer(ISSUER)
                        .build()
                        .verify(token);

                Long userId = Long.parseLong(decoded.getSubject());
                String roleClaim = decoded.getClaim("role").asString();

                AuthInfo authInfo = resolveAuthority(userId, roleClaim);

                if (authInfo != null) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    authInfo.email,
                                    null,
                                    List.of(new SimpleGrantedAuthority(authInfo.authority)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                log.debug("JWT verification failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * แปลง userId + role claim → email + Spring authority
     * ถ้า role claim มีอยู่จะใช้ตาม claim (เร็วกว่า)
     * ถ้าไม่มี (token เก่า) ตรวจจาก DB
     */
    private AuthInfo resolveAuthority(Long userId, String roleClaim) {
        // Path A: token ใหม่ มี role claim → trust the claim
        if ("ADMIN".equals(roleClaim)) {
            return adminRepository.findById(userId)
                    .map(a -> new AuthInfo(a.getEmail(), "ROLE_ADMIN"))
                    .orElse(null);
        }
        if ("USER".equals(roleClaim)) {
            return userRepository.findById(userId)
                    .map(u -> new AuthInfo(u.getEmail(), "ROLE_USER"))
                    .orElse(null);
        }

        // Path B: token เก่า ไม่มี role claim → fallback ตรวจจาก DB
        Optional<AdminEntity> admin = adminRepository.findById(userId);
        if (admin.isPresent()) {
            return new AuthInfo(admin.get().getEmail(), "ROLE_ADMIN");
        }
        Optional<UserEntity> user = userRepository.findById(userId);
        if (user.isPresent()) {
            return new AuthInfo(user.get().getEmail(), "ROLE_USER");
        }
        return null;
    }

    private record AuthInfo(String email, String authority) {}
}
