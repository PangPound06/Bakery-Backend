package com.app.my_project.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.OrderReportService;
import com.app.my_project.service.OrderReportService.OrderFilter;

/**
 * Endpoint สำหรับ Manage Orders + Report ที่ scale ได้ (paginate + filter + aggregate ฝั่ง server)
 * แยกออกจาก OrderController เดิมเพื่อไม่กระทบ endpoint ที่ใช้งานอยู่
 *
 *  - GET /api/orders/admin/page            → orders ทีละหน้า + filter
 *  - GET /api/orders/admin/report-summary  → KPI + นับสถานะ + ยอดขายรายวัน
 */
@RestController
@RequestMapping("/api/orders")
@CrossOrigin
public class OrderReportController {

    private final OrderReportService reportService;
    private final JwtService jwtService;
    private final AdminRepository adminRepository;

    public OrderReportController(OrderReportService reportService,
            JwtService jwtService,
            AdminRepository adminRepository) {
        this.reportService = reportService;
        this.jwtService = jwtService;
        this.adminRepository = adminRepository;
    }

    @GetMapping("/admin/page")
    public ResponseEntity<?> page(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (!isAdmin(auth))
            return forbidden();

        OrderFilter filter = new OrderFilter();
        filter.status = status;
        filter.channel = channel;
        filter.search = search;
        filter.from = parseDateTime(from, false);
        filter.to = parseDateTime(to, true);

        Page<OrderEntity> result = reportService.pagedSearch(filter, page, size);

        List<Long> ids = reportService.idsOf(result.getContent());
        Map<Long, List<Map<String, Object>>> itemsByOrder = reportService.itemsByOrderIds(ids);

        List<Map<String, Object>> content = new ArrayList<>();
        for (OrderEntity o : result.getContent()) {
            List<Map<String, Object>> items = itemsByOrder.getOrDefault(o.getId(), new ArrayList<>());
            content.add(orderToMap(o, items));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/admin/report-summary")
    public ResponseEntity<?> reportSummary(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (!isAdmin(auth))
            return forbidden();

        LocalDateTime fromDt = parseDateTime(from, false);
        LocalDateTime toDt = parseDateTime(to, true);
        return ResponseEntity.ok(reportService.summary(fromDt, toDt));
    }

    // ── helpers ──────────────────────────────────────────────

    /**
     * รองรับทั้ง "YYYY-MM-DD" และ "YYYY-MM-DDTHH:mm:ss"
     * ถ้าเป็นวันที่ล้วน: from → 00:00:00, to → 23:59:59
     */
    private LocalDateTime parseDateTime(String value, boolean endOfDay) {
        if (value == null || value.isBlank())
            return null;
        try {
            if (value.length() == 10) {
                LocalDate d = LocalDate.parse(value);
                return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
            }
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAdmin(String authHeader) {
        if (!jwtService.isAdmin(authHeader))
            return false;
        Long userId = jwtService.getUserIdFromHeader(authHeader);
        return userId != null && adminRepository.existsById(userId);
    }

    private ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
    }

    /** map เป็น JSON shape เดียวกับ /api/orders/all เดิม (items ส่งมาให้แล้ว) */
    private Map<String, Object> orderToMap(OrderEntity o, List<Map<String, Object>> items) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.getId());
        m.put("ordCode", o.getOrdCode());
        m.put("email", o.getEmail());
        m.put("subtotal", o.getSubtotal());
        m.put("shipping", o.getShipping());
        m.put("total", o.getTotal());
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("paymentStatus", o.getPaymentStatus());
        m.put("paymentId", o.getPaymentId());
        m.put("orderType", o.getOrderType());
        m.put("orderStatus", o.getOrderStatus());
        m.put("slipImage", o.getSlipImage());
        m.put("cardName", o.getCardName());
        m.put("cardLast4", o.getCardLast4());
        m.put("receiverName", o.getReceiverName());
        m.put("receiverPhone", o.getReceiverPhone());
        m.put("receiverAddress", o.getReceiverAddress());
        m.put("note", o.getNote());
        m.put("createdAt", o.getCreatedAt());
        m.put("items", items);
        return m;
    }
}