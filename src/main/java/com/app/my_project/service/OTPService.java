package com.app.my_project.service;

import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OTPService {
    // เก็บ OTP ใน memory
    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();
    
    // OTP หมดอายุใน 5 นาที
    private static final int OTP_EXPIRY_MINUTES = 5;
    
    // สร้าง OTP 6 หลัก
    public String generateOtp(String email) {
        SecureRandom random = new SecureRandom();
        int otp = 100000 + random.nextInt(900000);
        String otpString = String.valueOf(otp);
        
        otpStorage.put(email.toLowerCase(), new OtpData(otpString, LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES)));
        
        return otpString;
    }
    
    // ตรวจสอบ OTP
    public boolean verifyOtp(String email, String otp) {
        OtpData otpData = otpStorage.get(email.toLowerCase());
        
        if (otpData == null) {
            return false;
        }
        
        if (LocalDateTime.now().isAfter(otpData.expiryTime)) {
            otpStorage.remove(email.toLowerCase());
            return false;
        }
        
        if (otpData.otp.equals(otp)) {
            otpStorage.remove(email.toLowerCase());
            return true;
        }
        
        return false;
    }
    
    // ลบ OTP
    public void removeOtp(String email) {
        otpStorage.remove(email.toLowerCase());
    }
    
    // ตรวจสอบว่ามี OTP อยู่และยังไม่หมดอายุ
    public boolean hasValidOtp(String email) {
        OtpData otpData = otpStorage.get(email.toLowerCase());
        if (otpData == null) {
            return false;
        }
        return LocalDateTime.now().isBefore(otpData.expiryTime);
    }
    
    // Class สำหรับเก็บข้อมูล OTP
    private static class OtpData {
        String otp;
        LocalDateTime expiryTime;
        
        OtpData(String otp, LocalDateTime expiryTime) {
            this.otp = otp;
            this.expiryTime = expiryTime;
        }
    }
}
