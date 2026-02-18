package com.app.my_project.controller;

import com.app.my_project.entity.UserProfileEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.UserProfileRepository;
import com.app.my_project.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/profile")
@CrossOrigin(origins = "*")
public class UserProfileController {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    // ดึง Profile ของ User
    @GetMapping("/{userId}")
    public ResponseEntity<?> getProfile(@PathVariable Long userId) {
        Map<String, Object> response = new HashMap<>();

        Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(userId);

        if (profileOpt.isPresent()) {
            response.put("success", true);
            response.put("profile", profileOpt.get());
            return ResponseEntity.ok(response);
        }

        // ถ้ายังไม่มี profile → สร้างจาก UserEntity
        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();

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

        response.put("success", false);
        response.put("message", "ไม่พบผู้ใช้");
        return ResponseEntity.badRequest().body(response);
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
}
