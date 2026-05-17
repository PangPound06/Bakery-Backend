package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.entity.FavoriteEntity;
import com.app.my_project.repository.FavoriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * แก้ไขจากเดิม:
 *  - ลบ @CrossOrigin
 *  - Constructor injection
 *  - SLF4J logger แทน e.printStackTrace
 *  - ใช้ ApiResponse
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private static final Logger log = LoggerFactory.getLogger(FavoriteController.class);

    private final FavoriteRepository favoriteRepository;

    public FavoriteController(FavoriteRepository favoriteRepository) {
        this.favoriteRepository = favoriteRepository;
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<FavoriteEntity>> getFavorites(@PathVariable Long userId) {
        return ResponseEntity.ok(favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @PostMapping("/toggle")
    @Transactional
    public ResponseEntity<Map<String, Object>> toggleFavorite(@RequestBody Map<String, Object> request) {
        try {
            Long userId = Long.parseLong(request.get("userId").toString());
            Long productId = Long.parseLong(request.get("productId").toString());

            if (favoriteRepository.existsByUserIdAndProductId(userId, productId)) {
                favoriteRepository.deleteByUserIdAndProductId(userId, productId);
                return ApiResponse.ok("ลบออกจากรายการโปรดแล้ว", Map.of("action", "removed"));
            }

            FavoriteEntity favorite = new FavoriteEntity();
            favorite.setUserId(userId);
            favorite.setProductId(productId);
            favorite.setProductName((String) request.get("productName"));
            favorite.setProductImage((String) request.get("productImage"));
            favorite.setPrice(Double.parseDouble(request.get("price").toString()));
            favorite.setCategory((String) request.get("category"));
            favorite.setType((String) request.get("type"));
            favoriteRepository.save(favorite);

            return ApiResponse.ok("เพิ่มเข้ารายการโปรดแล้ว", Map.of("action", "added"));
        } catch (Exception e) {
            log.error("Failed to toggle favorite", e);
            return ApiResponse.badRequest("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    @GetMapping("/check/{userId}/{productId}")
    public ResponseEntity<Map<String, Object>> checkFavorite(
            @PathVariable Long userId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(Map.of(
                "isFavorite", favoriteRepository.existsByUserIdAndProductId(userId, productId)));
    }

    @DeleteMapping("/{userId}/{productId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeFavorite(
            @PathVariable Long userId,
            @PathVariable Long productId) {
        try {
            favoriteRepository.deleteByUserIdAndProductId(userId, productId);
            return ApiResponse.ok("ลบออกจากรายการโปรดแล้ว");
        } catch (Exception e) {
            log.error("Failed to remove favorite userId={} productId={}", userId, productId, e);
            return ApiResponse.badRequest("เกิดข้อผิดพลาด");
        }
    }
}
