package com.app.my_project;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.app.my_project.filter.JwtAuthFilter;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // ✅ ใช้ CORS filter ที่ register ด้วย @Bean — ทำงานก่อน Security filters
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // ✅ stateless — ไม่ใช้ session (เราใช้ JWT)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ✅ JwtAuthFilter ทำงานหลัง CORS แต่ก่อน UsernamePasswordAuthenticationFilter
                .addFilterBefore(
                        new JwtAuthFilter(jwtSecret, userRepository, adminRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // ✅ สำคัญ: OPTIONS request ต้อง permit (CORS preflight)
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .anyRequest().permitAll());
        return http.build();
    }

    /**
     * ✅ ใช้ @Bean CorsFilter เพื่อให้ register เป็น servlet filter ที่ระดับ application
     * ทำงาน "ก่อน" Spring Security filter chain ทั้งหมด
     * รวมถึงก่อน JwtAuthFilter — กัน 401/403 ที่ไม่มี CORS headers
     */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfig());
        return new CorsFilter(source);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfig());
        return source;
    }

    /** Shared CORS config — ใช้ทั้ง CorsFilter และ Spring Security */
    private CorsConfiguration buildCorsConfig() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "https://poundbakery.vercel.app",
                "https://*.vercel.app"));

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // ✅ "*" รับทุก header (ปลอดภัยกว่า hard-coded list)
        config.setAllowedHeaders(List.of("*"));

        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        return config;
    }
}