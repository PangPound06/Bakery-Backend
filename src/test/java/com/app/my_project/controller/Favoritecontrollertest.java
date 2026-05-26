package com.app.my_project.controller;

import com.app.my_project.entity.FavoriteEntity;
import com.app.my_project.repository.FavoriteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test FavoriteController
 *
 * Focus:
 *  - toggle logic: ถ้ามีอยู่ → ลบ, ถ้าไม่มี → เพิ่ม
 *  - validation: missing required fields
 *  - error handling
 */
@ExtendWith(MockitoExtension.class)
class FavoriteControllerTest {

    @Mock private FavoriteRepository favoriteRepository;

    @InjectMocks
    private FavoriteController controller;

    private Map<String, Object> toggleRequest(Long userId, Long productId) {
        Map<String, Object> req = new HashMap<>();
        req.put("userId", userId);
        req.put("productId", productId);
        req.put("productName", "ขนมเค้กช็อกโกแลต");
        req.put("productImage", "image.jpg");
        req.put("price", 150.0);
        req.put("category", "เค้ก");
        req.put("type", "cake");
        return req;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/favorites/user/{userId}")
    class GetFavoritesTests {

        @Test
        @DisplayName("คืน list ของ favorite ของ user")
        void getFavorites_returnsListForUser() {
            FavoriteEntity fav = new FavoriteEntity();
            fav.setUserId(1L);
            fav.setProductId(10L);

            when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(1L))
                    .thenReturn(List.of(fav));

            ResponseEntity<List<FavoriteEntity>> response = controller.getFavorites(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            verify(favoriteRepository).findByUserIdOrderByCreatedAtDesc(1L);
        }

        @Test
        @DisplayName("user ไม่มี favorite → คืน list ว่าง")
        void getFavorites_emptyList() {
            when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(99L))
                    .thenReturn(List.of());

            ResponseEntity<List<FavoriteEntity>> response = controller.getFavorites(99L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/favorites/toggle")
    class ToggleFavoriteTests {

        @Test
        @DisplayName("✅ ยังไม่ favorite → เพิ่มใหม่ (action=added)")
        void toggle_notExists_addsAndReturnsAdded() {
            when(favoriteRepository.existsByUserIdAndProductId(1L, 10L)).thenReturn(false);

            ResponseEntity<?> response = controller.toggleFavorite(toggleRequest(1L, 10L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsEntry("action", "added");

            // ตรวจว่ามีการ save จริงพร้อม field ครบ
            ArgumentCaptor<FavoriteEntity> captor = ArgumentCaptor.forClass(FavoriteEntity.class);
            verify(favoriteRepository).save(captor.capture());
            FavoriteEntity saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(1L);
            assertThat(saved.getProductId()).isEqualTo(10L);
            assertThat(saved.getProductName()).isEqualTo("ขนมเค้กช็อกโกแลต");
            assertThat(saved.getPrice()).isEqualTo(150.0);

            // ไม่ได้เรียก delete
            verify(favoriteRepository, never()).deleteByUserIdAndProductId(any(), any());
        }

        @Test
        @DisplayName("✅ มี favorite อยู่แล้ว → ลบออก (action=removed)")
        void toggle_alreadyExists_removesAndReturnsRemoved() {
            when(favoriteRepository.existsByUserIdAndProductId(1L, 10L)).thenReturn(true);

            ResponseEntity<?> response = controller.toggleFavorite(toggleRequest(1L, 10L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(castBody(response)).containsEntry("action", "removed");

            verify(favoriteRepository).deleteByUserIdAndProductId(1L, 10L);
            verify(favoriteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Missing userId → 400")
        void toggle_missingUserId_returns400() {
            Map<String, Object> req = new HashMap<>();
            req.put("productId", 10L);
            // ไม่ใส่ userId

            ResponseEntity<?> response = controller.toggleFavorite(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(favoriteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Price ไม่ใช่ตัวเลข → 400 (NumberFormatException ถูก catch)")
        void toggle_invalidPrice_returns400() {
            when(favoriteRepository.existsByUserIdAndProductId(1L, 10L)).thenReturn(false);

            Map<String, Object> req = toggleRequest(1L, 10L);
            req.put("price", "abc"); // ไม่ใช่ตัวเลข

            ResponseEntity<?> response = controller.toggleFavorite(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/favorites/check/{userId}/{productId}")
    class CheckFavoriteTests {

        @Test
        @DisplayName("เป็น favorite → isFavorite=true")
        void check_exists_returnsTrue() {
            when(favoriteRepository.existsByUserIdAndProductId(1L, 10L)).thenReturn(true);

            ResponseEntity<?> response = controller.checkFavorite(1L, 10L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(castBody(response)).containsEntry("isFavorite", true);
        }

        @Test
        @DisplayName("ไม่ใช่ favorite → isFavorite=false")
        void check_notExists_returnsFalse() {
            when(favoriteRepository.existsByUserIdAndProductId(1L, 10L)).thenReturn(false);

            ResponseEntity<?> response = controller.checkFavorite(1L, 10L);

            assertThat(castBody(response)).containsEntry("isFavorite", false);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /api/favorites/{userId}/{productId}")
    class RemoveFavoriteTests {

        @Test
        @DisplayName("ลบสำเร็จ → 200")
        void remove_success_returns200() {
            doNothing().when(favoriteRepository).deleteByUserIdAndProductId(1L, 10L);

            ResponseEntity<?> response = controller.removeFavorite(1L, 10L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(favoriteRepository).deleteByUserIdAndProductId(1L, 10L);
        }

        @Test
        @DisplayName("Repository throw exception → 400")
        void remove_repoThrows_returns400() {
            doThrow(new RuntimeException("DB error"))
                    .when(favoriteRepository).deleteByUserIdAndProductId(any(), any());

            ResponseEntity<?> response = controller.removeFavorite(1L, 10L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}