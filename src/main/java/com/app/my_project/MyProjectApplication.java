package com.app.my_project;

import java.time.Clock;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

// ใช้ JWT ของเราเอง (JwtAuthFilter) จึงไม่ต้องการ user/password ที่ Spring Security สร้างให้อัตโนมัติ
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling // UnpaidOrderSweeper
public class MyProjectApplication {

	public static void main(String[] args) {
		// บังคับให้เซิร์ฟเวอร์ทำงานในเวลาประเทศไทยทั้งหมด (ต้องตั้งก่อน Spring สร้าง bean ใดๆ)
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
		SpringApplication.run(MyProjectApplication.class, args);
	}

	/** นาฬิกาของระบบ (เวลาไทยตาม default ข้างบน) — service ที่ตัดสินเรื่องเวลารับผ่านตัวนี้เพื่อให้ทดสอบได้ */
	@Bean
	Clock clock() {
		return Clock.systemDefaultZone();
	}
}
