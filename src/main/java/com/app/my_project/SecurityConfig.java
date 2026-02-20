package com.app.my_project;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // 👈 เปลี่ยนมาเรียกใช้ Bean ที่เราสร้างด้านล่าง
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").permitAll()
                .requestMatchers("/uploads/**").permitAll()
                .anyRequest().permitAll()
            );
        
        return http.build();
    }

    // 👈 เพิ่ม Bean สำหรับตั้งค่า CORS Global อย่างละเอียด
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 1. อนุญาตเฉพาะโดเมน Frontend ของคุณ (และ localhost สำหรับตอนเทสในเครื่อง)
        configuration.setAllowedOrigins(Arrays.asList(
            "https://bakery-frontend-next.vercel.app", 
            "http://localhost:3000"
        )); 
        
        // 2. อนุญาต Method ที่จำเป็นทั้งหมด (OPTIONS สำคัญมากสำหรับแก้ CORS)
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        
        // 3. อนุญาต Headers ทั้งหมด
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "x-requested-with"));
        
        // 4. อนุญาตให้ส่งข้อมูลยืนยันตัวตน (เช่น Cookies) 
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // บังคับใช้กับทุก API
        
        return source;
    }
}