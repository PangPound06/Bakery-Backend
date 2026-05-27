package com.app.my_project.service;

import com.app.my_project.common.ProductQuantityHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    private static final String TOP_PRODUCTS_SQL =
            "SELECT oi.product_name, oi.price, oi.quantity, oi.selected_option, " +
            "  o.created_at, o.subtotal, " +
            "  COALESCE(p.category, 'unknown') as category, " +
            "  (SELECT SUM(oi2.price * oi2.quantity) FROM tb_order_items oi2 " +
            "    WHERE oi2.order_id = o.id) as order_items_total " +
            "FROM tb_order_items oi " +
            "JOIN tb_orders o ON oi.order_id = o.id " +
            "LEFT JOIN tb_products p ON oi.product_id = p.id " +
            "WHERE ( " +
            "  (o.order_type = 'pos' AND o.order_status != 'cancelled') " +
            "  OR " +
            "  (o.order_status IN ('confirmed', 'preparing', 'shipping', 'delivered')) " +
            ")";

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

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(TOP_PRODUCTS_SQL);

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
            double itemRaw = price * quantity;
            double ratio = orderItemsTotal > 0 ? itemRaw / orderItemsTotal : 0;
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
                .limit(10)
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