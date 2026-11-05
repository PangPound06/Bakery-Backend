package com.app.my_project.service;

import com.app.my_project.common.ProductQuantityHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OrderStatsService — wraps complex aggregation SQL for /stats/top-products
 *
 * เหตุผลที่แยกออก:
 *  - Raw SQL ซับซ้อน (JOIN 3 tables + nested subquery) — แปลงเป็น JPA ไม่คุ้ม
 *  - แยกออกมาให้ controller ไม่ต้องรู้จัก JdbcTemplate
 *  - test ใช้ Mockito บน JdbcTemplate ได้ง่าย
 */
@Service
public class OrderStatsService {

    /**
     * นับเฉพาะของที่ขายได้จริง
     *
     * ╔══ remaining คืออะไร ══╗
     * tb_order_items.quantity คือจำนวนที่สั่งตอนแรก ไม่ลดตามการยกเลิก
     * ของที่คืนเงินไปแล้วอยู่ใน cancelled_qty จึงต้องหักออกก่อนนับ
     * ไม่งั้นเค้กที่คืนเงินไปแล้วจะยังขึ้นอันดับสินค้าขายดีอยู่
     *
     * ╔══ ทำไมหารด้วย CASE ══╗
     * quantity เก็บเป็น "ชิ้น" แต่ price เป็นราคาต่อ 1 ออเดอร์
     * order_items_total ที่เอาไปหารสัดส่วนรายได้จึงต้องคิดเป็นออเดอร์เหมือนกัน
     * ไม่งั้นเค้กปอนด์จะกินสัดส่วนไป 8–16 เท่า แล้วรายได้ของสินค้าอื่นในบิลเดียวกันจะหาย
     *
     * ตัวคูณต้องตรงกับ ProductQuantityHelper — แก้ที่นี่ต้องแก้ที่นั่นด้วย
     */
    private static final String QTY_MULTIPLIER_SQL =
            "CASE WHEN oi.selected_option LIKE '%2 ปอนด์%' THEN 16 " +
            "     WHEN oi.selected_option LIKE '%1 ปอนด์%' THEN 8 " +
            "     ELSE 1 END";

    private static final String REMAINING_RAW_SQL =
            "GREATEST(oi.quantity - COALESCE(oi.cancelled_qty, 0), 0)";

    private static final String TOP_PRODUCTS_SQL =
            "WITH line AS ( " +
            "  SELECT oi.order_id, oi.product_id, oi.product_name, oi.price, oi.selected_option, " +
            "         " + REMAINING_RAW_SQL + " AS remaining_raw, " +
            "         " + REMAINING_RAW_SQL + " / (" + QTY_MULTIPLIER_SQL + ") AS remaining_units " +
            "  FROM tb_order_items oi " +
            ") " +
            "SELECT line.product_name, line.price, line.selected_option, " +
            "  line.remaining_raw AS quantity, " +
            "  o.created_at, o.subtotal, " +
            "  COALESCE(p.category, 'unknown') as category, " +
            // ✅ window function — คำนวณ order_items_total ต่อ order ใน pass เดียว
            //    (แทน correlated subquery ที่รันซ้ำต่อแถว)
            "  SUM(line.price * line.remaining_units) OVER (PARTITION BY line.order_id) as order_items_total " +
            "FROM line " +
            "JOIN tb_orders o ON line.order_id = o.id " +
            "LEFT JOIN tb_products p ON line.product_id = p.id " +
            "WHERE ( " +
            "  (o.order_type = 'pos' AND o.order_status != 'cancelled') " +
            "  OR " +
            // cancel_requested นับด้วย — ยังไม่มีใครคืนเงิน ถ้าตัดออกตัวเลขจะกระพริบตอนแอดมินปฏิเสธ
            "  (o.order_status IN ('confirmed', 'preparing', 'shipping', 'delivered', 'cancel_requested')) " +
            ") " +
            // ของที่ยกเลิกหมดทั้งรายการแล้ว ตัดทิ้งไปเลย ไม่งั้น orderCount จะนับเกิน
            "AND line.remaining_units > 0 " +
            // ✅ กรองช่วงวันใน SQL — days=7/30 จะสแกนเฉพาะช่วงที่ต้องการ
            "AND o.created_at >= ?";

    private final JdbcTemplate jdbcTemplate;
    private final ProductQuantityHelper qtyHelper;

    public OrderStatsService(JdbcTemplate jdbcTemplate, ProductQuantityHelper qtyHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.qtyHelper = qtyHelper;
    }

    /**
     * ดึง top 10 products + total summary
     * @param days "all" | "7" | "30"
     */
    public TopProductsResult getTopProducts(String days) {
        LocalDateTime cutoff = resolveCutoff(days);
        Timestamp cutoffTs = toCutoffTimestamp(cutoff);

        // ✅ กรองวันที่ใน SQL แล้ว (ดึงเฉพาะช่วงที่ต้องการ) — ไม่ดึง order_items ทั้งตารางมา loop
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(TOP_PRODUCTS_SQL, cutoffTs);

        // Aggregation: group โดยใช้ key = productName|||selectedOption|||category
        Map<String, long[]> grouped = new LinkedHashMap<>(); // [qty, revenue, orderCount]

        for (Map<String, Object> row : rows) {
            // Filter ตาม cutoff date
            LocalDateTime createdAt = toLocalDateTime(row.get("created_at"));
            if (createdAt == null || createdAt.isBefore(cutoff)) continue;

            String productName = (String) row.get("product_name");
            double price = ((Number) row.get("price")).doubleValue();
            int quantity = ((Number) row.get("quantity")).intValue();
            String selectedOption = normalizeOption((String) row.get("selected_option"));
            String category = (String) row.get("category");

            double orderSubtotal = ((Number) row.get("subtotal")).doubleValue();
            Object oitObj = row.get("order_items_total");
            double orderItemsTotal = oitObj != null ? ((Number) oitObj).doubleValue() : 0.0;

            int displayQty = qtyHelper.toDisplayQty(quantity, selectedOption);
            // มูลค่าของบรรทัดนี้ — ต้องคิดเป็น "ออเดอร์" ให้ตรงหน่วยกับ order_items_total
            double itemValue = price * displayQty;
            double ratio = orderItemsTotal > 0 ? itemValue / orderItemsTotal : 0;
            long revenue = (long) (orderSubtotal * ratio);

            String key = productName + "|||"
                    + (selectedOption != null ? selectedOption : "") + "|||"
                    + category;

            grouped.putIfAbsent(key, new long[]{0, 0, 0});
            long[] data = grouped.get(key);
            data[0] += displayQty;
            data[1] += revenue;
            data[2] += 1;
        }

        // Total summary
        long totalRevenue = grouped.values().stream().mapToLong(d -> d[1]).sum();
        long totalQty = grouped.values().stream().mapToLong(d -> d[0]).sum();
        long totalOrders = grouped.values().stream().mapToLong(d -> d[2]).sum();

        // Top 10 by qty
        List<TopProduct> topList = grouped.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|\\|\\|", 3);
                    return new TopProduct(
                            parts[0],
                            parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null,
                            parts.length > 2 ? parts[2] : "unknown",
                            entry.getValue()[0],
                            entry.getValue()[1],
                            entry.getValue()[2]
                    );
                })
                .toList();

        return new TopProductsResult(
                topList, totalRevenue, totalQty, totalOrders, grouped.size()
        );
    }

    // ─── Helpers (package-private สำหรับ test) ────────────────────

    /** แปลง cutoff → Timestamp สำหรับ SQL param; "all" (MIN) → floor ปี 1970 (เท่ากับไม่กรอง) */
    private Timestamp toCutoffTimestamp(LocalDateTime cutoff) {
        if (cutoff == null || cutoff.isEqual(LocalDateTime.MIN)) {
            return Timestamp.valueOf(LocalDateTime.of(1970, 1, 1, 0, 0));
        }
        return Timestamp.valueOf(cutoff);
    }

    LocalDateTime resolveCutoff(String days) {
        if (days == null || "all".equals(days)) return LocalDateTime.MIN;
        return switch (days) {
            case "7" -> LocalDateTime.now().minusDays(7);
            case "30" -> LocalDateTime.now().minusDays(30);
            default -> LocalDateTime.MIN;
        };
    }

    String normalizeOption(String option) {
        if (option == null) return null;
        if (option.contains("1 ปอนด์")) return "1 ปอนด์ (8 ชิ้น)";
        if (option.contains("2 ปอนด์")) return "2 ปอนด์ (16 ชิ้น)";
        return option;
    }

    private LocalDateTime toLocalDateTime(Object timestamp) {
        if (timestamp == null) return null;
        if (timestamp instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (timestamp instanceof LocalDateTime ldt) return ldt;
        return null;
    }

    // ─── DTOs ──────────────────────────────────────────────────────

    public record TopProduct(
            String productName,
            String selectedOption,
            String category,
            long totalQty,
            long totalRevenue,
            long orderCount
    ) {}

    public record TopProductsResult(
            List<TopProduct> topProducts,
            long totalAllRevenue,
            long totalAllQty,
            long totalAllOrders,
            int totalProductCount
    ) {}
}