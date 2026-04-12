package com.app.my_project.controller;

import com.app.my_project.entity.UserProfileEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.UserProfileRepository;
import com.app.my_project.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

@RestController
@RequestMapping("/api/profile")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class UserProfileController {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${jwt.secret}")
    private String jwtSecret;

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

    // ✅ Helper: decode JWT → ได้ userId
    private Long getUserIdFromToken(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer "))
                return null;
            String token = authHeader.replace("Bearer ", "");
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(jwtSecret)).build();
            String subject = verifier.verify(token).getSubject();
            return Long.parseLong(subject);
        } catch (Exception e) {
            return null;
        }
    }

    // ✅ Helper: ดึงหรือสร้าง profile จาก userId
    private UserProfileEntity getOrCreateProfile(Long userId) {
        Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(userId);
        if (profileOpt.isPresent())
            return profileOpt.get();

        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty())
            return null;

        UserEntity user = userOpt.get();

        Optional<UserProfileEntity> profileByEmail = userProfileRepository.findByEmail(user.getEmail());
        if (profileByEmail.isPresent()) {
            UserProfileEntity profile = profileByEmail.get();
            profile.setUserId(userId);
            return userProfileRepository.save(profile);
        }

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(userId);
        profile.setFullname(user.getFullname());
        profile.setEmail(user.getEmail());
        profile.setProfileImage(user.getProfileImage());
        return userProfileRepository.save(profile);
    }

    private Map<String, Object> toSafeProfile(UserProfileEntity profile) {
        Map<String, Object> safe = new HashMap<>();
        safe.put("fullname", profile.getFullname());
        safe.put("email", profile.getEmail());
        safe.put("phone", profile.getPhone());
        safe.put("address", profile.getAddress());
        safe.put("profileImage", profile.getProfileImage());
        safe.put("updatedAt", profile.getUpdatedAt());
        return safe;
    }

    // GET /api/profile/me
    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(@RequestHeader("Authorization") String authHeader) {
        Map<String, Object> response = new HashMap<>();
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        UserProfileEntity profile = getOrCreateProfile(userId);
        if (profile == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "ไม่พบผู้ใช้"));

        response.put("success", true);
        response.put("profile", toSafeProfile(profile)); // ✅ ซ่อน id และ userId
        return ResponseEntity.ok(response);
    }

    // PUT /api/profile/me
    @PutMapping("/me")
    public ResponseEntity<?> updateMyProfile(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        try {
            UserProfileEntity profile = getOrCreateProfile(userId);
            if (profile == null)
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "ไม่พบผู้ใช้"));

            if (request.containsKey("fullname")) {
                profile.setFullname(request.get("fullname"));
                userRepository.findById(userId).ifPresent(user -> {
                    user.setFullname(request.get("fullname"));
                    userRepository.save(user);
                });
            }
            if (request.containsKey("phone"))
                profile.setPhone(request.get("phone"));
            if (request.containsKey("address"))
                profile.setAddress(request.get("address"));

            userProfileRepository.save(profile);

            response.put("success", true);
            response.put("message", "บันทึกข้อมูลสำเร็จ");
            response.put("profile", toSafeProfile(profile)); // ✅ ซ่อน id และ userId
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    // POST /api/profile/me/image
    @PostMapping("/me/image")
    public ResponseEntity<?> uploadMyProfileImage(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        try {
            byte[] bytes = file.getBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> uploadResult = getCloudinary().uploader().upload(
                    bytes,
                    ObjectUtils.asMap("folder", "bakery/profiles", "public_id", "profile_" + userId, "overwrite",
                            true));

            String imageUrl = (String) uploadResult.get("secure_url");

            userProfileRepository.findByUserId(userId).ifPresent(profile -> {
                profile.setProfileImage(imageUrl);
                userProfileRepository.save(profile);
            });

            response.put("success", true);
            response.put("url", imageUrl);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "อัปโหลดไม่สำเร็จ: " + e.getMessage()));
        }
    }

    // DELETE /api/profile/me/image
    @DeleteMapping("/me/image")
    public ResponseEntity<?> deleteMyProfileImage(@RequestHeader("Authorization") String authHeader) {
        Map<String, Object> response = new HashMap<>();
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));

        try {
            userProfileRepository.findByUserId(userId).ifPresent(profile -> {
                try {
                    getCloudinary().uploader().destroy("bakery/profiles/profile_" + userId, ObjectUtils.emptyMap());
                } catch (Exception ignored) {
                }
                profile.setProfileImage(null);
                userProfileRepository.save(profile);
            });

            response.put("success", true);
            response.put("message", "ลบรูปภาพสำเร็จ");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // OLD endpoints — backward compatibility
    @GetMapping("/{userId}")
    public ResponseEntity<?> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long userId) {
        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!tokenUserId.equals(userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์เข้าถึงข้อมูลนี้"));

        Map<String, Object> response = new HashMap<>();
        if (userRepository.findById(userId).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "ไม่พบผู้ใช้"));

        UserProfileEntity profile = getOrCreateProfile(userId);
        response.put("success", true);
        response.put("profile", toSafeProfile(profile));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{userId}")
    public ResponseEntity<?> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!tokenUserId.equals(userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์แก้ไขข้อมูลนี้"));

        Map<String, Object> response = new HashMap<>();
        try {
            UserProfileEntity profile = getOrCreateProfile(userId);
            if (profile == null)
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "ไม่พบผู้ใช้"));

            if (request.containsKey("fullname")) {
                profile.setFullname(request.get("fullname"));
                userRepository.findById(userId).ifPresent(user -> {
                    user.setFullname(request.get("fullname"));
                    userRepository.save(user);
                });
            }
            if (request.containsKey("phone"))
                profile.setPhone(request.get("phone"));
            if (request.containsKey("address"))
                profile.setAddress(request.get("address"));
            userProfileRepository.save(profile);

            response.put("success", true);
            response.put("message", "บันทึกข้อมูลสำเร็จ");
            response.put("profile", toSafeProfile(profile));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "เกิดข้อผิดพลาด"));
        }
    }

    @PostMapping("/{userId}/image")
    public ResponseEntity<?> uploadProfileImage(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long userId,
            @RequestParam("file") MultipartFile file) {
        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!tokenUserId.equals(userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์อัปโหลดรูปนี้"));

        Map<String, Object> response = new HashMap<>();
        try {
            byte[] bytes = file.getBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> uploadResult = getCloudinary().uploader().upload(
                    bytes, ObjectUtils.asMap("folder", "bakery/profiles", "public_id", "profile_" + userId, "overwrite",
                            true));
            String imageUrl = (String) uploadResult.get("secure_url");
            userProfileRepository.findByUserId(userId).ifPresent(profile -> {
                profile.setProfileImage(imageUrl);
                userProfileRepository.save(profile);
            });
            response.put("success", true);
            response.put("url", imageUrl);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "อัปโหลดไม่สำเร็จ"));
        }
    }

    @DeleteMapping("/{userId}/image")
    public ResponseEntity<?> deleteProfileImage(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long userId) {
        Long tokenUserId = getUserIdFromToken(authHeader);
        if (tokenUserId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "กรุณาเข้าสู่ระบบ"));
        if (!tokenUserId.equals(userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "ไม่มีสิทธิ์ลบรูปนี้"));

        Map<String, Object> response = new HashMap<>();
        try {
            userProfileRepository.findByUserId(userId).ifPresent(profile -> {
                try {
                    getCloudinary().uploader().destroy("bakery/profiles/profile_" + userId, ObjectUtils.emptyMap());
                } catch (Exception ignored) {
                }
                profile.setProfileImage(null);
                userProfileRepository.save(profile);
            });
            response.put("success", true);
            response.put("message", "ลบรูปภาพสำเร็จ");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}