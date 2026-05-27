package com.app.my_project.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test StockService — focuses on:
 * - SQL parameters ส่งถูกต้อง (productId, qty, FRESH_STOCK constant)
 * - return value forward จาก JdbcTemplate
 * - EmptyResultDataAccessException ถูก catch ใน findImage
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private StockService stockService;

    @Nested
    @DisplayName("decreaseStock()")
    class DecreaseTests {

        @Test
        @DisplayName("✅ ส่ง qty + productId + FRESH_STOCK (9999) ตามลำดับ")
        void sendsCorrectParams() {
            when(jdbcTemplate.update(anyString(), eq(5), eq(5), eq(10L), eq(9999)))
                    .thenReturn(1);

            int affected = stockService.decreaseStock(10L, 5);

            assertThat(affected).isEqualTo(1);
            verify(jdbcTemplate).update(
                    contains("GREATEST"), // ใช้ GREATEST() กัน negative
                    eq(5), eq(5), eq(10L), eq(9999));
        }

        @Test
        @DisplayName("Update affected 0 rows (เช่น product FRESH_STOCK) → return 0")
        void freshStockProduct_returns0() {
            when(jdbcTemplate.update(anyString(), anyInt(), anyInt(), anyLong(), anyInt()))
                    .thenReturn(0);

            int affected = stockService.decreaseStock(99L, 1);

            assertThat(affected).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("increaseStock()")
    class IncreaseTests {

        @Test
        @DisplayName("✅ ส่ง qty + productId + FRESH_STOCK ตามลำดับ")
        void sendsCorrectParams() {
            when(jdbcTemplate.update(anyString(), eq(3), eq(20L), eq(9999)))
                    .thenReturn(1);

            int affected = stockService.increaseStock(20L, 3);

            assertThat(affected).isEqualTo(1);
            verify(jdbcTemplate).update(
                    contains("isAvailable"),
                    eq(3), eq(20L), eq(9999));
        }
    }

    @Nested
    @DisplayName("batchIncreaseStock()")
    class BatchIncreaseTests {

        @Test
        @DisplayName("✅ ใช้ batchUpdate ไม่ใช่ loop ทีละ row")
        void usesBatchUpdate() {
            List<StockService.StockChange> items = List.of(
                    new StockService.StockChange(1L, 2),
                    new StockService.StockChange(2L, 3),
                    new StockService.StockChange(3L, 5));

            when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                    .thenReturn(new int[] { 1, 1, 1 });

            int[] result = stockService.batchIncreaseStock(items);

            assertThat(result).hasSize(3);
            // ✅ verify ว่าเรียก batchUpdate (เร็วกว่า loop update)
            verify(jdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
            // ไม่เรียก update ทีละครั้ง
            verify(jdbcTemplate, never()).update(anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("Empty list → ไม่เรียก DB")
        void emptyList_noDbCall() {
            int[] result = stockService.batchIncreaseStock(List.of());

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("null list → ไม่เรียก DB")
        void nullList_noDbCall() {
            int[] result = stockService.batchIncreaseStock(null);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }
    }

    @Nested
    @DisplayName("findImage()")
    class FindImageTests {

        @Test
        @DisplayName("Product มีรูป → return URL")
        void existingProduct_returnsImage() {
            when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(10L)))
                    .thenReturn("https://cdn.com/cake.jpg");

            String image = stockService.findImage(10L);

            assertThat(image).isEqualTo("https://cdn.com/cake.jpg");
        }

        @Test
        @DisplayName("✅ Product ไม่มี (EmptyResultDataAccessException) → null (ไม่ throw)")
        void notFound_returnsNull() {
            when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyLong()))
                    .thenThrow(new EmptyResultDataAccessException(1));

            String image = stockService.findImage(999L);

            assertThat(image).isNull();
        }

        @Test
        @DisplayName("✅ DB error → null (ไม่ propagate exception)")
        void dbError_returnsNull() {
            when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyLong()))
                    .thenThrow(new RuntimeException("DB down"));

            String image = stockService.findImage(1L);

            assertThat(image).isNull();
        }
    }
}