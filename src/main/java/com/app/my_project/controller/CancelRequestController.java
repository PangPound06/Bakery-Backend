package com.app.my_project.controller;

import com.app.my_project.common.CurrentUser;
import com.app.my_project.service.CancelRequestService;
import com.app.my_project.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CancelRequestController
 *
 * ═══ ฝั่งลูกค้า ═══
 * POST /api/cancel-requests/preview → ดูว่าจะได้คืนเท่าไหร่ (ไม่บันทึกอะไร)
 * POST /api/cancel-requests → ยื่นคำขอ
 * GET /api/cancel-requests/mine → ดูคำขอของตัวเอง
 *
 * ═══ ฝั่งแอดมิน ═══
 * GET /api/admin/cancel-requests?status=pending
 * GET /api/admin/cancel-requests/{id}/items
 * POST /api/admin/orders/{orderId}/cancel-refund
 * POST /api/admin/cancel-requests/{id}/approve
 * POST /api/admin/cancel-requests/{id}/reject
 *
 * ลูกค้ายกเลิกเองไม่ได้ — ทำได้แค่ยื่นคำขอ
 * การคืนเงินจริงเกิดขึ้นตอนแอดมินกดอนุมัติเท่านั้น
 */
@RestController
@RequestMapping("/api")
public class CancelRequestController {

    private static final Logger log = LoggerFactory.getLogger(CancelRequestController.class);

    private final CancelRequestService cancelRequestService;
    private final CurrentUser currentUser;

    public CancelRequestController(CancelRequestService cancelRequestService, CurrentUser currentUser) {
        this.cancelRequestService = cancelRequestService;
        this.currentUser = currentUser;
    }

    // ═════════════════════════════════════════════════════════════════
    // ลูกค้า
    // ═════════════════════════════════════════════════════════════════

    @PostMapping("/cancel-requests/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        try {
            Long orderId = asLong(body.get("orderId"));
            CancelRequestService.Preview p = cancelRequestService.preview(
                    email, orderId, parseItems(body.get("items")));

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("scope", p.scope());
            resp.put("grossAmount", p.grossAmount());
            resp.put("feeDeducted", p.feeDeducted());
            resp.put("refundAmount", p.refundAmount());
            resp.put("includesShipping", p.includesShipping());
            return ResponseEntity.ok(resp);

        } catch (CancelRequestService.CancelException | RefundService.RefundException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("preview คำขอยกเลิกไม่สำเร็จ (email={})", email, e);
            return error("ไม่สามารถคำนวณยอดคืนเงินได้ กรุณาลองใหม่");
        }
    }

    @PostMapping("/cancel-requests")
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();

        try {
            Long orderId = asLong(body.get("orderId"));
            Long id = cancelRequestService.submit(
                    email, orderId,
                    parseItems(body.get("items")),
                    str(body.get("reasonCode")),
                    str(body.get("reasonNote")));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "requestId", id,
                    "message", "ส่งคำขอยกเลิกแล้ว ทางร้านจะพิจารณาและแจ้งผลให้ทราบ"));

        } catch (CancelRequestService.CancelException | RefundService.RefundException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("ยื่นคำขอยกเลิกไม่สำเร็จ (email={})", email, e);
            return error("ไม่สามารถส่งคำขอได้ กรุณาลองใหม่");
        }
    }

    @GetMapping("/cancel-requests/mine")
    public ResponseEntity<?> mine(@RequestHeader(value = "Authorization", required = false) String auth) {
        String email = resolveEmail(auth);
        if (email == null)
            return unauthorized();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "requests", cancelRequestService.listByEmail(email)));
    }

    // ═════════════════════════════════════════════════════════════════
    // แอดมิน
    // ═════════════════════════════════════════════════════════════════

    @GetMapping("/admin/cancel-requests")
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "requests", cancelRequestService.listByStatus(status)));
    }

    @GetMapping("/admin/cancel-requests/{id}/items")
    public ResponseEntity<?> items(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!currentUser.isAdmin(auth))
            return forbidden();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "items", cancelRequestService.itemsOf(id)));
    }

    /**
     * body: { mode: "fee_deducted" | "full" | "manual", amount?: number, note?: string }
     *
     * fee_deducted = หักค่าธรรมเนียม Stripe (ลูกค้าเปลี่ยนใจ)
     * full = คืนเต็มจำนวน ร้านรับค่าธรรมเนียมเอง (ของหมด ร้านทำไม่ได้)
     * manual = แอดมินระบุยอดเอง
     */
    @PostMapping("/admin/cancel-requests/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<String> admin = currentUser.adminEmail(auth);
        if (admin.isEmpty())
            return forbidden();

        String adminEmail = admin.get();
        Map<String, Object> b = body == null ? Map.of() : body;

        try {
            CancelRequestService.Result r = cancelRequestService.approve(
                    id,
                    str(b.get("mode")),
                    asDouble(b.get("amount")),
                    str(b.get("note")),
                    adminEmail);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "requestId", r.requestId(),
                    "orderId", r.orderId(),
                    "refundAmount", r.refundAmount(),
                    "feeDeducted", r.feeDeducted(),
                    "manualPayout", r.manualPayout(),
                    "message", refundMessage("คืนเงิน", r)));

        } catch (CancelRequestService.CancelException | RefundService.RefundException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("อนุมัติคำขอ #{} ไม่สำเร็จ", id, e);
            return error("คืนเงินไม่สำเร็จ กรุณาตรวจสอบใน Stripe Dashboard ก่อนลองใหม่");
        }
    }

    /**
     * แอดมินยกเลิกคำสั่งซื้อที่จ่ายเงินแล้วเอง พร้อมคืนเงินในครั้งเดียว
     *
     * ใช้ตอนของหมดกะทันหันหรือร้านทำให้ไม่ได้ — ไม่ต้องรอให้ลูกค้ายื่นคำขอก่อน
     *
     * body: { items?, reasonCode?, note?, mode?, amount? }
     * ไม่ส่ง items มา = ยกเลิกทั้งใบ, mode เริ่มต้น full (ร้านรับค่าธรรมเนียมเอง)
     */
    @PostMapping("/admin/orders/{orderId}/cancel-refund")
    public ResponseEntity<?> adminCancelOrder(@PathVariable Long orderId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<String> admin = currentUser.adminEmail(auth);
        if (admin.isEmpty())
            return forbidden();

        String adminEmail = admin.get();
        Map<String, Object> b = body == null ? Map.of() : body;

        try {
            CancelRequestService.Result r = cancelRequestService.cancelByAdmin(
                    orderId,
                    parseItems(b.get("items")),
                    str(b.get("reasonCode")),
                    str(b.get("note")),
                    str(b.get("mode")),
                    asDouble(b.get("amount")),
                    adminEmail);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "requestId", r.requestId(),
                    "orderId", r.orderId(),
                    "refundAmount", r.refundAmount(),
                    "feeDeducted", r.feeDeducted(),
                    "manualPayout", r.manualPayout(),
                    "message", refundMessage("ยกเลิกและคืนเงิน", r)));

        } catch (CancelRequestService.CancelException | RefundService.RefundException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("แอดมินยกเลิก order #{} ไม่สำเร็จ", orderId, e);
            return error("คืนเงินไม่สำเร็จ กรุณาตรวจสอบใน Stripe Dashboard ก่อนลองใหม่");
        }
    }

    @PostMapping("/admin/cancel-requests/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Optional<String> admin = currentUser.adminEmail(auth);
        if (admin.isEmpty())
            return forbidden();

        String adminEmail = admin.get();
        Map<String, Object> b = body == null ? Map.of() : body;

        try {
            CancelRequestService.Result r = cancelRequestService.reject(
                    id, str(b.get("note")), adminEmail);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "requestId", r.requestId(),
                    "orderId", r.orderId(),
                    "message", "ปฏิเสธคำขอแล้ว"));

        } catch (CancelRequestService.CancelException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("ปฏิเสธคำขอ #{} ไม่สำเร็จ", id, e);
            return error("ไม่สามารถดำเนินการได้ กรุณาลองใหม่");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /** email ของลูกค้าที่ login — ยื่น/ดูคำขอได้เฉพาะของตัวเอง */
    private String resolveEmail(String authHeader) {
        return currentUser.customerEmail(authHeader).orElse(null);
    }

    /** order ที่โอนพร้อมเพย์เข้าบัญชีร้านตรง ระบบโอนคืนให้ไม่ได้ — ต้องบอกแอดมินให้โอนเอง */
    private static String refundMessage(String action, CancelRequestService.Result r) {
        String done = String.format("%s %.2f บาทเรียบร้อย", action, r.refundAmount());
        return r.manualPayout()
                ? done + " — กรุณาโอนคืนลูกค้าเองผ่านแอปธนาคาร (คำสั่งซื้อนี้ชำระด้วยการโอนพร้อมเพย์)"
                : done;
    }

    @SuppressWarnings("unchecked")
    private List<CancelRequestService.ItemRequest> parseItems(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            return List.of();

        List<CancelRequestService.ItemRequest> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map))
                continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Long itemId = asLong(m.get("orderItemId"));
            Double qty = asDouble(m.get("quantity"));
            if (itemId == null || qty == null)
                continue;
            out.add(new CancelRequestService.ItemRequest(itemId, qty.intValue()));
        }
        return out;
    }

    private static Long asLong(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number n)
            return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error", "กรุณาเข้าสู่ระบบ"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "error", "ต้องเป็นแอดมินเท่านั้น"));
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", message));
    }

    private ResponseEntity<?> error(String message) {
        return ResponseEntity.internalServerError().body(Map.of("success", false, "error", message));
    }
}