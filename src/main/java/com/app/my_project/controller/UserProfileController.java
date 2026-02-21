package com.app.my_project.controller;

import com.app.my_project.entity.UserProfileEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.UserProfileRepository;
import com.app.my_project.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@CrossOrigin(origins = "*")
public class UserProfileController {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    private Cloudinary getCloudinary() {
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret));
    }

    // ดึง Profile ของ User
    @GetMapping("/{userId}")
    public ResponseEntity<?> getProfile(@PathVariable Long userId) {
        Map<String, Object> response = new HashMap<>();

        // ✅ ดึง User ก่อนเพื่อเอา Email
        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบผู้ใช้");
            return ResponseEntity.badRequest().body(response);
        }

        UserEntity user = userOpt.get();

        // ✅ ค้นหา Profile จาก userId ก่อน
        Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(userId);
        if (profileOpt.isPresent()) {
            response.put("success", true);
            response.put("profile", profileOpt.get());
            return ResponseEntity.ok(response);
        }

        // ✅ ถ้าไม่มี → เช็คจาก Email (กรณี Profile เก่าค้างอยู่)
        Optional<UserProfileEntity> profileByEmail = userProfileRepository.findByEmail(user.getEmail());
        if (profileByEmail.isPresent()) {
            // อัปเดต userId ให้ตรง
            UserProfileEntity profile = profileByEmail.get();
            profile.setUserId(userId);
            userProfileRepository.save(profile);
            response.put("success", true);
            response.put("profile", profile);
            return ResponseEntity.ok(response);
        }

        // สร้างใหม่
        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(userId);
        profile.setFullname(user.getFullname());
        profile.setEmail(user.getEmail());
        profile.setProfileImage(user.getProfileImage());
        userProfileRepository.save(profile);

        response.put("success", true);
        response.put("profile", profile);
        return ResponseEntity.ok(response);
    }

    // อัพเดท Profile
    @PutMapping("/{userId}")
    public ResponseEntity<?> updateProfile(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            UserProfileEntity profile;
            Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(userId);

            if (profileOpt.isPresent()) {
                profile = profileOpt.get();
            } else {
                // สร้างใหม่ถ้ายังไม่มี
                profile = new UserProfileEntity();
                profile.setUserId(userId);

                Optional<UserEntity> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent()) {
                    profile.setEmail(userOpt.get().getEmail());
                }
            }

            // อัพเดทข้อมูล
            if (request.containsKey("fullname")) {
                profile.setFullname(request.get("fullname"));

                // อัพเดทชื่อใน tb_userregister ด้วย
                Optional<UserEntity> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent()) {
                    UserEntity user = userOpt.get();
                    user.setFullname(request.get("fullname"));
                    userRepository.save(user);
                }
            }
            if (request.containsKey("phone")) {
                profile.setPhone(request.get("phone"));
            }
            if (request.containsKey("address")) {
                profile.setAddress(request.get("address"));
            }

            userProfileRepository.save(profile);

            response.put("success", true);
            response.put("message", "บันทึกข้อมูลสำเร็จ");
            response.put("profile", profile);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // อัปโหลดรูปโปรไฟล์
    @PostMapping("/{userId}/image")
    public ResponseEntity<?> uploadProfileImage(
            @PathVariable Long userId,
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> response = new HashMap<>();

        try {
            byte[] bytes = file.getBytes();

            Map uploadResult = getCloudinary().uploader().upload(
                    bytes,
                    ObjectUtils.asMap(
                            "folder", "bakery/profiles",
                            "public_id", "profile_" + userId,
                            "overwrite", true));

            String imageUrl = (String) uploadResult.get("secure_url");

            // อัปเดตใน tb_user_profile
            Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(userId);
            if (profileOpt.isPresent()) {
                UserProfileEntity profile = profileOpt.get();
                profile.setProfileImage(imageUrl);
                userProfileRepository.save(profile);
            }

            response.put("success", true);
            response.put("url", imageUrl);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "อัปโหลดไม่สำเร็จ: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ลบรูปโปรไฟล์
    @DeleteMapping("/{userId}/image")
    public ResponseEntity<?> deleteProfileImage(@PathVariable Long userId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(userId);
            if (profileOpt.isPresent()) {
                UserProfileEntity profile = profileOpt.get();

                // ลบจาก Cloudinary
                String publicId = "bakery/profiles/profile_" + userId;
                getCloudinary().uploader().destroy(publicId, ObjectUtils.emptyMap());

                // เคลียร์ URL ในฐานข้อมูล
                profile.setProfileImage(null);
                userProfileRepository.save(profile);
            }

            response.put("success", true);
            response.put("message", "ลบรูปภาพสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
