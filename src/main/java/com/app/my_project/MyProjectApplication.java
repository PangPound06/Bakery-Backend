package com.app.my_project;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@RestController
@ComponentScan(basePackages = "com.app.my_project")
public class MyProjectApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyProjectApplication.class, args);
	}

	@PostConstruct
    public void init() {
        // บังคับให้เซิร์ฟเวอร์ทำงานในเวลาประเทศไทยทั้งหมด
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
    }
}
