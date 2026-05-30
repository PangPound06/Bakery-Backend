package com.app.my_project.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * รองรับหน้า Manage Orders + Report แบบ scale ได้:
 *  - pagedSearch: ดึง orders ทีละหน้า + filter ที่ฝั่ง DB (ไม่ดึงทั้งตาราง)
 *  - itemsByOrderIds: ดึง items ของทั้งหน้าใน query เดียว (กัน N+1)
 *  - summary: รวมยอด KPI / นับสถานะ / ยอดขายรายวัน ด้วย SQL aggregation
 *             (คืนแค่ตัวเลขสรุป ไม่ส่งแถวดิบ → เร็วแม้ข้อมูลหลักล้าน)
 */
@Service
public class OrderReportService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final JdbcTemplate jdbcTemplate;

    public OrderReportService(OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            JdbcTemplate jdbcTemplate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── filter object ────────────────────────────────────────
    public static class OrderFilter {
        public String status; // all | pending | confirmed | preparing | shipping | delivered | cancelled
        public String channel; // all | online | pos | dine-in | dine-in-alacarte | dine-in-buffet
        public String search; // ordCode / email / receiverName
        public LocalDateTime from;
        public LocalDateTime to;
    }

    /** ดึง orders ทีละหน้า ตาม filter (เรียงใหม่สุดก่อน) */
    public Page<OrderEntity> pagedSearch(OrderFilter f, int page, int size) {
        Specification<OrderEntity> spec = buildSpec(f);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                size <= 0 ? 50 : Math.min(size, 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository.findAll(spec, pageable);
    }

    private Specification<OrderEntity> buildSpec(OrderFilter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (f.status != null && !f.status.isBlank() && !"all".equals(f.status)) {
                ps.add(cb.equal(root.get("orderStatus"), f.status));
            }

            if (f.channel != null && !f.channel.isBlank() && !"all".equals(f.channel)) {
                switch (f.channel) {
                    case "online":
                        // ออนไลน์ = ไม่ใช่ pos และไม่ใช่ dine-in (รวม null ด้วย)
                        ps.add(cb.or(
                                cb.isNull(root.get("orderType")),
                                cb.not(root.get("orderType").in("pos", "dine-in"))));
                        break;
                    case "pos":
                        ps.add(cb.equal(root.get("orderType"), "pos"));
                        break;
                    case "dine-in":
                        ps.add(cb.equal(root.get("orderType"), "dine-in"));
                        break;
                    case "dine-in-alacarte":
                        ps.add(cb.equal(root.get("orderType"), "dine-in"));
                        ps.add(cb.or(
                                cb.isNull(root.get("note")),
                                cb.notLike(root.get("note"), "%Buffet%")));
                        break;
                    case "dine-in-buffet":
                        ps.add(cb.equal(root.get("orderType"), "dine-in"));
                        ps.add(cb.like(root.get("note"), "%Buffet%"));
                        break;
                    default:
                        break;
                }
            }

            if (f.search != null && !f.search.isBlank()) {
                String like = "%" + f.search.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("ordCode")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("receiverName")), like)));
            }

            if (f.from != null)
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from));
            if (f.to != null)
                ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), f.to));

            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** ดึง items ของหลาย order พร้อมกัน → group ตาม orderId (1 query) */
    public Map<Long, List<Map<String, Object>>> itemsByOrderIds(List<Long> orderIds) {
        Map<Long, List<Map<String, Object>>> result = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty())
            return result;

        List<OrderItemEntity> items = orderItemRepository.findByOrderIdIn(orderIds);
        for (OrderItemEntity item : items) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            m.put("productId", item.getProductId());
            m.put("productName", item.getProductName());
            m.put("price", item.getPrice());
            m.put("quantity", item.getQuantity());
            m.put("selectedOption", item.getSelectedOption());
            // หมายเหตุ: list view ไม่ต้องใช้รูป → ไม่เรียก findImage (ลดภาระ DB)
            result.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(m);
        }
        return result;
    }

    /** สรุป KPI + นับสถานะ + ยอดขายรายวัน ด้วย SQL aggregation */
    public Map<String, Object> summary(LocalDateTime from, LocalDateTime to) {
        // ─ where clause สำหรับช่วงวันที่ ─
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            where.append(" AND created_at >= ? ");
            args.add(java.sql.Timestamp.valueOf(from));
        }
        if (to != null) {
            where.append(" AND created_at <= ? ");
            args.add(java.sql.Timestamp.valueOf(to));
        }
        Object[] a = args.toArray();

        // สถานะที่นับเป็น "ยอดขายจริง" — ตรงกับ validOrders ฝั่ง frontend
        String validStatuses = "('confirmed','preparing','shipping','delivered')";

        // KPI
        Double revenue = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total),0) FROM tb_orders" + where +
                        " AND order_status IN " + validStatuses,
                Double.class, a);
        Long validCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_orders" + where +
                        " AND order_status IN " + validStatuses,
                Long.class, a);
        Long totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_orders" + where, Long.class, a);
        Long successCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_orders" + where + " AND order_status = 'delivered'",
                Long.class, a);

        double rev = revenue != null ? revenue : 0.0;
        long valid = validCount != null ? validCount : 0L;
        long avg = valid > 0 ? Math.round(rev / valid) : 0;

        // นับตามสถานะ (สำหรับแท็บ filter)
        Map<String, Long> statusCounts = new HashMap<>();
        jdbcTemplate.queryForList(
                "SELECT order_status, COUNT(*) AS cnt FROM tb_orders" + where + " GROUP BY order_status", a)
                .forEach(row -> statusCounts.put(
                        String.valueOf(row.get("order_status")),
                        ((Number) row.get("cnt")).longValue()));

        // ยอดขายรายวัน (เวลาที่เก็บเป็น Asia/Bangkok อยู่แล้ว) — เฉพาะ validOrders
        List<Map<String, Object>> salesDaily = new ArrayList<>();
        jdbcTemplate.queryForList(
                "SELECT to_char(created_at, 'YYYY-MM-DD') AS day, " +
                        "COALESCE(SUM(total),0) AS revenue, COUNT(*) AS orders " +
                        "FROM tb_orders" + where + " AND order_status IN " + validStatuses +
                        " GROUP BY day ORDER BY day",
                a)
                .forEach(row -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("date", row.get("day"));
                    m.put("revenue", ((Number) row.get("revenue")).doubleValue());
                    m.put("orders", ((Number) row.get("orders")).longValue());
                    salesDaily.add(m);
                });

        // นับตามช่องทาง (สำหรับแท็บใน Manage Orders)
        Map<String, Long> channelCounts = new HashMap<>();
        Map<String, Object> ch = jdbcTemplate.queryForMap(
                "SELECT " +
                        "COUNT(*) AS total, " +
                        "COUNT(*) FILTER (WHERE order_type = 'pos') AS pos, " +
                        "COUNT(*) FILTER (WHERE order_type = 'dine-in') AS dinein, " +
                        "COUNT(*) FILTER (WHERE order_type IS NULL OR order_type NOT IN ('pos','dine-in')) AS online, " +
                        "COUNT(*) FILTER (WHERE order_type = 'dine-in' AND (note IS NULL OR note NOT LIKE '%Buffet%')) AS alacarte, "
                        +
                        "COUNT(*) FILTER (WHERE order_type = 'dine-in' AND note LIKE '%Buffet%') AS buffet " +
                        "FROM tb_orders" + where,
                a);
        channelCounts.put("all", ((Number) ch.get("total")).longValue());
        channelCounts.put("online", ((Number) ch.get("online")).longValue());
        channelCounts.put("pos", ((Number) ch.get("pos")).longValue());
        channelCounts.put("dineIn", ((Number) ch.get("dinein")).longValue());
        channelCounts.put("alacarte", ((Number) ch.get("alacarte")).longValue());
        channelCounts.put("buffet", ((Number) ch.get("buffet")).longValue());

        Map<String, Object> result = new HashMap<>();
        result.put("totalRevenue", rev);
        result.put("validOrderCount", valid);
        result.put("totalOrderCount", totalCount != null ? totalCount : 0L);
        result.put("successCount", successCount != null ? successCount : 0L);
        result.put("avgOrderValue", avg);
        result.put("statusCounts", statusCounts);
        result.put("channelCounts", channelCounts);
        result.put("salesDaily", salesDaily);
        return result;
    }

    /** helper: แปลง list ของ id (ใช้ที่ controller) */
    public List<Long> idsOf(List<OrderEntity> orders) {
        return orders.stream().map(OrderEntity::getId).collect(Collectors.toList());
    }
}