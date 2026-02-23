package com.app.my_project.controller;

import com.app.my_project.entity.FavoriteEntity;
import com.app.my_project.repository.FavoriteRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@RestController
@RequestMapping("/api/favorites")
@CrossOrigin(origins = "https://bakery-frontend-next.vercel.app")
public class FavoriteController {

    @Autowired
    private FavoriteRepository favoriteRepository;

    // ดึง Favorites ของ User
    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getFavorites(@PathVariable Long userId) {
        List<FavoriteEntity> favorites = favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(favorites);
    }

    // เพิ่ม / ลบ Favorite (Toggle)
    @PostMapping("/toggle")
    @Transactional
    public ResponseEntity<?> toggleFavorite(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            Long userId = Long.parseLong(request.get("userId").toString());
            Long productId = Long.parseLong(request.get("productId").toString());

            // ถ้ามีอยู่แล้ว → ลบ
            if (favoriteRepository.existsByUserIdAndProductId(userId, productId)) {
                favoriteRepository.deleteByUserIdAndProductId(userId, productId);
                response.put("success", true);
                response.put("action", "removed");
                response.put("message", "ลบออกจากรายการโปรดแล้ว");
                return ResponseEntity.ok(response);
            }

            // ถ้ายังไม่มี → เพิ่ม
            FavoriteEntity favorite = new FavoriteEntity();
            favorite.setUserId(userId);
            favorite.setProductId(productId);
            favorite.setProductName((String) request.get("productName"));
            favorite.setProductImage((String) request.get("productImage"));
            favorite.setPrice(Double.parseDouble(request.get("price").toString()));
            favorite.setCategory((String) request.get("category"));
            favorite.setType((String) request.get("type"));

            favoriteRepository.save(favorite);

            response.put("success", true);
            response.put("action", "added");
            response.put("message", "เพิ่มเข้ารายการโปรดแล้ว");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    // ตรวจสอบว่าสินค้าอยู่ใน Favorites หรือไม่
    @GetMapping("/check/{userId}/{productId}")
    public ResponseEntity<?> checkFavorite(
            @PathVariable Long userId,
            @PathVariable Long productId) {
        Map<String, Object> response = new HashMap<>();
        response.put("isFavorite", favoriteRepository.existsByUserIdAndProductId(userId, productId));
        return ResponseEntity.ok(response);
    }

    // ลบ Favorite
    @DeleteMapping("/{userId}/{productId}")
    @Transactional
    public ResponseEntity<?> removeFavorite(
            @PathVariable Long userId,
            @PathVariable Long productId) {
        Map<String, Object> response = new HashMap<>();

        try {
            favoriteRepository.deleteByUserIdAndProductId(userId, productId);
            response.put("success", true);
            response.put("message", "ลบออกจากรายการโปรดแล้ว");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.badRequest().body(response);
        }
    }
}
