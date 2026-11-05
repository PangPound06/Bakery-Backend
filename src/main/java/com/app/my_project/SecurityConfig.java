package com.app.my_project;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    /** ทุก method ต้องเป็นแอดมิน */
    private static final String[] ADMIN_ONLY = {
            "/api/admin/**",
            "/api/orders/admin/**",
            "/api/dinein/admin/**",
            "/api/reservations/admin/**",
            "/api/suppliers/**",
            "/api/purchase-orders/**",
            "/api/payment/**",
    };

    /** ลูกค้าอ่านได้ แต่สร้าง/แก้/ลบได้เฉพาะแอดมิน */
    private static final String[] ADMIN_WRITES = {
            "/api/products/**",
            "/api/categories/**",
            "/api/store/branches/**",
    };

    private final String jwtSecret;
    private final UserRepository userRepository;
    private final AdminRepository adminRepository;

    public SecurityConfig(
            @Value("${jwt.secret}") String jwtSecret,
            UserRepository userRepository,
            AdminRepository adminRepository) {
        this.jwtSecret = jwtSecret;
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
    }

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
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // ✅ Stripe webhook — ไม่มี JWT (Stripe ยิงมาเอง)
                        // ความปลอดภัยมาจากการ verify signature ใน controller
                        // ไม่ใช่จาก Spring Security
                        .requestMatchers("/api/stripe/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        // ═══ หลังบ้าน — ด่านแรกที่นี่ที่เดียว ═══
                        // controller บางตัวเช็คสิทธิ์ซ้ำอีกชั้น บางตัวไม่ได้เช็คเลย
                        // กฎตรงนี้กันไว้ทั้งหมด ลืมเช็คใน controller ใหม่ก็ไม่หลุด
                        .requestMatchers(ADMIN_ONLY).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, ADMIN_WRITES).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, ADMIN_WRITES).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, ADMIN_WRITES).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, ADMIN_WRITES).hasRole("ADMIN")
                        // อัปรูปเข้า Cloudinary ของร้าน — ต้อง login (แอดมินอัปรูปสินค้า / ลูกค้าอัปสลิป)
                        // แต่ลบรูปได้เฉพาะแอดมิน ไม่งั้นลูกค้าลบรูปสินค้าได้
                        .requestMatchers(HttpMethod.DELETE, "/api/upload/**").hasRole("ADMIN")
                        .requestMatchers("/api/upload/**").authenticated()
                        .anyRequest().permitAll());
        return http.build();
    }

    /**
     * ✅ ใช้ @Bean CorsFilter เพื่อให้ register เป็น servlet filter ที่ระดับ
     * application
     * ทำงาน "ก่อน" Spring Security filter chain ทั้งหมด
     * รวมถึงก่อน JwtAuthFilter — กัน 401/403 ที่ไม่มี CORS headers
     */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // ✅ Stripe webhook ไม่ได้มาจาก browser จึงไม่มี Origin header
        // ลงทะเบียน config เปล่าไว้ก่อน กัน CorsFilter ไปยุ่งกับมัน
        source.registerCorsConfiguration("/api/stripe/webhook", new CorsConfiguration());
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
                "https://*.vercel.app",
                "https://poundbakeryshop.com"));

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