package com.app.my_project.filter;

import com.app.my_project.entity.UserEntity;
import com.app.my_project.entity.AdminEntity;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.repository.AdminRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class JwtAuthFilter extends OncePerRequestFilter {

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

        if (header != null && header.startsWith("Bearer ")) {
            try {
                String token = header.substring(7);
                DecodedJWT decoded = JWT.require(Algorithm.HMAC256(jwtSecret))
                        .withIssuer("auth0")
                        .build()
                        .verify(token);

                // ✅ subject คือ userId → ดึง email จาก DB
                Long userId = Long.parseLong(decoded.getSubject());
                String email = null;

                Optional<UserEntity> user = userRepository.findById(userId);
                if (user.isPresent()) {
                    email = user.get().getEmail();
                } else {
                    Optional<AdminEntity> admin = adminRepository.findById(userId);
                    if (admin.isPresent()) email = admin.get().getEmail();
                }

                if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(email, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                // token ไม่ valid → ปล่อยผ่านแบบ anonymous
            }
        }

        filterChain.doFilter(request, response);
    }
}