package com.app.my_project;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configure(http))
            .authorizeHttpRequests(auth -> auth
                // เปิดให้เรียกได้โดยไม่ต้อง login
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/google",
                    "/api/auth/google/callback",
                    "/api/admin/login",
                    "/uploads/**"
                ).permitAll()
                // ทุกอย่างอื่นต้องมี JWT
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public OncePerRequestFilter jwtFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain)
                    throws ServletException, IOException {

                String path = request.getRequestURI();

                // ข้าม filter สำหรับ public endpoint
                if (path.startsWith("/api/auth/login") ||
                    path.startsWith("/api/auth/register") ||
                    path.startsWith("/api/auth/google") ||
                    path.startsWith("/api/admin/login") ||
                    path.startsWith("/api/products") ||  
                    path.startsWith("/uploads/")) {
                    chain.doFilter(request, response);
                    return;
                }

                String header = request.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    String token = header.substring(7);
                    try {
                        JWT.require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("auth0")
                                .build()
                                .verify(token);
                        chain.doFilter(request, response);
                    } catch (JWTVerificationException e) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"success\":false,\"message\":\"Token ไม่ถูกต้อง\"}");
                    }
                } else {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":false,\"message\":\"กรุณาเข้าสู่ระบบ\"}");
                }
            }
        };
    }
}