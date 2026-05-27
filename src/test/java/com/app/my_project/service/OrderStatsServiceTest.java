package com.app.my_project.service;

import com.app.my_project.common.ProductQuantityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test OrderStatsService
 *
 * Focus:
 *  - Aggregation logic (group by productName + selectedOption + category)
 *  - Top 10 limit + sort by qty
 *  - Date cutoff filter
 *  - normalizeOption (1 ปอนด์ → "1 ปอนด์ (8 ชิ้น)")
 *  - Revenue calculation: orderSubtotal * (itemRaw / orderItemsTotal)
 */
@ExtendWith(MockitoExtension.class)
class OrderStatsServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;

    // ใช้ real instance ของ helper เพราะเป็น pure logic
    private final ProductQuantityHelper qtyHelper = new ProductQuantityHelper();

    private OrderStatsService service;

    @BeforeEach
    void setUp() {
        service = new OrderStatsService(jdbcTemplate, qtyHelper);
    }

    /** Helper สร้าง row mock จาก SQL result */
    private Map<String, Object> row(String productName, double price, int quantity,
                                     String selectedOption, LocalDateTime createdAt,
                                     double orderSubtotal, double orderItemsTotal,
                                     String category) {
        Map<String, Object> r = new HashMap<>();
        r.put("product_name", productName);
        r.put("price", price);
        r.put("quantity", quantity);
        r.put("selected_option", selectedOption);
        r.put("created_at", Timestamp.valueOf(createdAt));
        r.put("subtotal", orderSubtotal);
        r.put("order_items_total", orderItemsTotal);
        r.put("category", category);
        return r;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getTopProducts() — basic")
    class BasicTests {

        @Test
        @DisplayName("Rows ว่าง → empty result")
        void emptyRows_returnsEmpty() {
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).isEmpty();
            assertThat(result.totalAllRevenue()).isZero();
            assertThat(result.totalAllQty()).isZero();
            assertThat(result.totalAllOrders()).isZero();
            assertThat(result.totalProductCount()).isZero();
        }

        @Test
        @DisplayName("✅ Single product: qty + revenue + orderCount ถูกต้อง")
        void singleProduct_correctAggregates() {
            // 1 row: cake 1 ปอนด์, raw qty 8 (= 1 display), price 100, orderSubtotal 100
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Cake", 100.0, 8, "1 ปอนด์", LocalDateTime.now(), 100.0, 800.0, "cake")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).hasSize(1);
            OrderStatsService.TopProduct top = result.topProducts().get(0);
            assertThat(top.productName()).isEqualTo("Cake");
            assertThat(top.totalQty()).isEqualTo(1); // 8 raw / 8 = 1 display
            assertThat(top.orderCount()).isEqualTo(1);
            // revenue = 100 (orderSubtotal) * (100*8 / 800) = 100 * 1 = 100
            assertThat(top.totalRevenue()).isEqualTo(100);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Aggregation by key")
    class AggregationTests {

        @Test
        @DisplayName("✅ Same productName+option+category → group เป็น 1 entry")
        void sameKey_groupedIntoOne() {
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Cake", 100.0, 8, "1 ปอนด์", LocalDateTime.now(), 100.0, 800.0, "cake"),
                    row("Cake", 100.0, 16, "1 ปอนด์", LocalDateTime.now(), 200.0, 1600.0, "cake")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).hasSize(1);
            assertThat(result.topProducts().get(0).totalQty()).isEqualTo(3); // 1 + 2
            assertThat(result.topProducts().get(0).orderCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("✅ ต่าง option (1 vs 2 ปอนด์) → แยกเป็น 2 entries")
        void differentOption_separateEntries() {
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Cake", 100.0, 8, "1 ปอนด์", LocalDateTime.now(), 100.0, 800.0, "cake"),
                    row("Cake", 200.0, 16, "2 ปอนด์", LocalDateTime.now(), 200.0, 3200.0, "cake")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).hasSize(2);
        }

        @Test
        @DisplayName("✅ Sort by totalQty DESC")
        void sortedByQtyDesc() {
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("ProductA", 100.0, 1, null, LocalDateTime.now(), 100.0, 100.0, "cat"),
                    row("ProductB", 100.0, 5, null, LocalDateTime.now(), 500.0, 500.0, "cat"),
                    row("ProductC", 100.0, 3, null, LocalDateTime.now(), 300.0, 300.0, "cat")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).hasSize(3);
            assertThat(result.topProducts().get(0).productName()).isEqualTo("ProductB");
            assertThat(result.topProducts().get(1).productName()).isEqualTo("ProductC");
            assertThat(result.topProducts().get(2).productName()).isEqualTo("ProductA");
        }

        @Test
        @DisplayName("✅ Limit top 10 — ถ้ามี 15 products คืนแค่ 10")
        void limitTo10() {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                rows.add(row("P" + i, 100.0, i, null, LocalDateTime.now(), 100.0, 100.0, "cat"));
            }
            when(jdbcTemplate.queryForList(anyString())).thenReturn(rows);

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).hasSize(10);
            // totalProductCount = 15 (ทั้งหมด ไม่ใช่แค่ top 10)
            assertThat(result.totalProductCount()).isEqualTo(15);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Date cutoff filter")
    class CutoffTests {

        @Test
        @DisplayName("✅ days='7' → กรอง order เก่ากว่า 7 วัน")
        void days7_filtersOldOrders() {
            LocalDateTime old = LocalDateTime.now().minusDays(10);
            LocalDateTime recent = LocalDateTime.now().minusDays(3);

            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Old Order", 100.0, 1, null, old, 100.0, 100.0, "cat"),
                    row("Recent Order", 100.0, 1, null, recent, 100.0, 100.0, "cat")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("7");

            assertThat(result.topProducts()).hasSize(1);
            assertThat(result.topProducts().get(0).productName()).isEqualTo("Recent Order");
        }

        @Test
        @DisplayName("✅ days='30' → กรอง order เก่ากว่า 30 วัน")
        void days30_filtersOldOrders() {
            LocalDateTime old = LocalDateTime.now().minusDays(40);
            LocalDateTime recent = LocalDateTime.now().minusDays(20);

            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Very Old", 100.0, 1, null, old, 100.0, 100.0, "cat"),
                    row("Last Month", 100.0, 1, null, recent, 100.0, 100.0, "cat")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("30");

            assertThat(result.topProducts()).hasSize(1);
            assertThat(result.topProducts().get(0).productName()).isEqualTo("Last Month");
        }

        @Test
        @DisplayName("days='all' → ไม่กรอง")
        void daysAll_noFilter() {
            LocalDateTime veryOld = LocalDateTime.now().minusYears(5);

            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Ancient", 100.0, 1, null, veryOld, 100.0, 100.0, "cat")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts()).hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("normalizeOption() — helper")
    class NormalizeTests {

        @Test
        @DisplayName("null → null")
        void nullOption_returnsNull() {
            assertThat(service.normalizeOption(null)).isNull();
        }

        @Test
        @DisplayName("'1 ปอนด์' → '1 ปอนด์ (8 ชิ้น)'")
        void onePound_normalized() {
            assertThat(service.normalizeOption("1 ปอนด์")).isEqualTo("1 ปอนด์ (8 ชิ้น)");
        }

        @Test
        @DisplayName("'2 ปอนด์' → '2 ปอนด์ (16 ชิ้น)'")
        void twoPound_normalized() {
            assertThat(service.normalizeOption("2 ปอนด์")).isEqualTo("2 ปอนด์ (16 ชิ้น)");
        }

        @Test
        @DisplayName("Option อื่น → คงเดิม")
        void unknownOption_passthrough() {
            assertThat(service.normalizeOption("ขนาดเล็ก")).isEqualTo("ขนาดเล็ก");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("resolveCutoff() — helper")
    class CutoffResolveTests {

        @Test
        @DisplayName("null → MIN (ไม่กรอง)")
        void nullDays_min() {
            assertThat(service.resolveCutoff(null)).isEqualTo(LocalDateTime.MIN);
        }

        @Test
        @DisplayName("'all' → MIN")
        void all_min() {
            assertThat(service.resolveCutoff("all")).isEqualTo(LocalDateTime.MIN);
        }

        @Test
        @DisplayName("'7' → now - 7 days (approximately)")
        void days7_minusSeven() {
            LocalDateTime expected = LocalDateTime.now().minusDays(7);
            LocalDateTime actual = service.resolveCutoff("7");
            assertThat(actual).isCloseTo(expected, within(2, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("'30' → now - 30 days")
        void days30_minusThirty() {
            LocalDateTime expected = LocalDateTime.now().minusDays(30);
            LocalDateTime actual = service.resolveCutoff("30");
            assertThat(actual).isCloseTo(expected, within(2, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("Value แปลก ('999') → MIN")
        void invalidDays_min() {
            assertThat(service.resolveCutoff("999")).isEqualTo(LocalDateTime.MIN);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Revenue calculation")
    class RevenueTests {

        @Test
        @DisplayName("✅ Revenue = orderSubtotal * (itemRaw / orderItemsTotal)")
        void revenueProportional() {
            // price=50, qty=2 → itemRaw = 100
            // orderItemsTotal = 200, orderSubtotal = 150
            // ratio = 100/200 = 0.5 → revenue = 150 * 0.5 = 75
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Product", 50.0, 2, null, LocalDateTime.now(), 150.0, 200.0, "cat")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts().get(0).totalRevenue()).isEqualTo(75);
        }

        @Test
        @DisplayName("orderItemsTotal = 0 → revenue = 0 (กัน divide by zero)")
        void zeroItemsTotal_zeroRevenue() {
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    row("Product", 50.0, 2, null, LocalDateTime.now(), 150.0, 0.0, "cat")
            ));

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            assertThat(result.topProducts().get(0).totalRevenue()).isZero();
        }

        @Test
        @DisplayName("✅ Total summary = sum ของทุก products (ไม่ใช่แค่ top 10)")
        void totalSummary_includesAll() {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                rows.add(row("P" + i, 100.0, i, null, LocalDateTime.now(), 100.0, 100.0, "cat"));
            }
            when(jdbcTemplate.queryForList(anyString())).thenReturn(rows);

            OrderStatsService.TopProductsResult result = service.getTopProducts("all");

            // total qty = 1+2+...+15 = 120
            assertThat(result.totalAllQty()).isEqualTo(120);
            assertThat(result.totalAllOrders()).isEqualTo(15);
            assertThat(result.totalProductCount()).isEqualTo(15);
        }
    }
}