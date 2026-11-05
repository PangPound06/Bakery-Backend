package com.app.my_project.service;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/**
 * CancelRequestService — flow ขอยกเลิก / อนุมัติ / ปฏิเสธ
 *
 * ═══ ทำไมลูกค้ายกเลิกเองไม่ได้ ═══
 * ถ้าให้ยกเลิกเองแล้วคืนเงินอัตโนมัติ ร้านจะเสียค่าธรรมเนียมทุกครั้ง
 * และเค้กที่อบไปแล้วขายต่อไม่ได้ จึงให้ยื่นคำขอแล้วแอดมินตัดสิน
 *
 * ═══ ระหว่างรออนุมัติ ของยังถูกจองอยู่ ═══
 * order_status เปลี่ยนเป็น cancel_requested แต่ stock ยังไม่คืน
 * เพราะถ้าคืนแล้วแอดมินปฏิเสธ จะกลายเป็นขายของที่ไม่มี
 * stock คืนตอนอนุมัติเท่านั้น
 */
@Service
public class CancelRequestService {

    private static final Logger log = LoggerFactory.getLogger(CancelRequestService.class);

    /** ขอยกเลิกได้เฉพาะตอนที่ของยังไม่เข้ากระบวนการผลิต */
    private static final Set<String> CANCELLABLE_STATUSES = Set.of("pending", "confirmed");

    /**
     * สามตัวแรกเป็นเหตุผลที่ลูกค้าเลือกได้เอง
     * out_of_stock / shop_cancelled เป็นเหตุฝั่งร้าน ใช้ตอนแอดมินยกเลิกให้เอง
     */
    private static final Set<String> REASON_CODES = Set.of(
            "wrong_item", "changed_mind", "duplicate",
            "delivery_issue", "too_slow", "other",
            "out_of_stock", "shop_cancelled");

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    public static final String ORDER_STATUS_REQUESTED = "cancel_requested";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RefundService refundService;
    private final StockService stockService;
    private final ProductQuantityHelperAdapter qtyHelper;
    private final JdbcTemplate jdbcTemplate;

    public CancelRequestService(OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            RefundService refundService,
            StockService stockService,
            com.app.my_project.common.ProductQuantityHelper helper,
            JdbcTemplate jdbcTemplate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.refundService = refundService;
        this.stockService = stockService;
        this.qtyHelper = new ProductQuantityHelperAdapter(helper);
        this.jdbcTemplate = jdbcTemplate;
    }

    // ═════════════════════════════════════════════════════════════════
    // ลูกค้า — ดูยอดก่อนยืนยัน
    // ═════════════════════════════════════════════════════════════════

    /**
     * คำนวณให้ลูกค้าเห็นก่อนกดยืนยันว่าจะได้คืนเท่าไหร่
     * ไม่บันทึกอะไรลง DB
     */
    public Preview preview(String email, Long orderId, List<ItemRequest> items) throws Exception {
        OrderEntity order = requireOwnedOrder(email, orderId);
        requireCancellable(order);

        boolean full = items == null || items.isEmpty();
        Double itemsAmount = full ? null : sumRequestedItems(order, items).totalBaht();

        RefundService.Quote q = refundService.quote(order, itemsAmount);

        return new Preview(
                full ? "full" : "partial",
                q.grossBaht(),
                q.feeShareBaht(),
                q.suggestedBaht(),
                full);
    }

    // ═════════════════════════════════════════════════════════════════
    // ลูกค้า — ยื่นคำขอ
    // ═════════════════════════════════════════════════════════════════

    @Transactional
    public Long submit(String email, Long orderId, List<ItemRequest> items,
            String reasonCode, String reasonNote) throws Exception {

        OrderEntity order = requireOwnedOrder(email, orderId);
        requireCancellable(order);

        if (reasonCode == null || !REASON_CODES.contains(reasonCode)) {
            throw new CancelException("กรุณาเลือกเหตุผลในการยกเลิก");
        }
        if ("other".equals(reasonCode) && (reasonNote == null || reasonNote.isBlank())) {
            throw new CancelException("กรุณาระบุเหตุผลเพิ่มเติม");
        }
        if (hasPendingRequest(orderId)) {
            throw new CancelException("คำสั่งซื้อนี้มีคำขอยกเลิกที่รอการพิจารณาอยู่แล้ว");
        }

        boolean full = items == null || items.isEmpty();
        Resolved resolved = full ? null : sumRequestedItems(order, items);
        Double itemsAmount = full ? null : resolved.totalBaht();

        RefundService.Quote q = refundService.quote(order, itemsAmount);

        // ─── บันทึกคำขอ ───
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tb_cancel_requests " +
                            "(order_id, email, scope, reason_code, reason_note, estimated_refund, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, email);
            ps.setString(3, full ? "full" : "partial");
            ps.setString(4, reasonCode);
            ps.setString(5, reasonNote);
            ps.setDouble(6, q.suggestedBaht());
            ps.setString(7, STATUS_PENDING);
            return ps;
        }, keys);

        Long requestId = extractId(keys);

        // ─── บันทึกรายการที่ขอยกเลิก ───
        if (!full) {
            for (ResolvedLine line : resolved.lines()) {
                jdbcTemplate.update(
                        "INSERT INTO tb_cancel_request_items " +
                                "(request_id, order_item_id, quantity, line_amount) VALUES (?, ?, ?, ?)",
                        requestId, line.orderItemId(), line.rawQuantity(), line.amountBaht());
            }
        }

        // ─── ทำเครื่องหมายว่ามีคำขอค้าง (ยังไม่ยกเลิกจริง ของยังจองอยู่) ───
        order.setOrderStatus(ORDER_STATUS_REQUESTED);
        orderRepository.save(order);

        log.info("📩 order {} มีคำขอยกเลิก #{} ({}) จาก {} — ยอดที่คำนวณได้ {} บาท",
                orderId, requestId, full ? "ทั้งใบ" : "บางรายการ", email, q.suggestedBaht());

        return requestId;
    }

    // ═════════════════════════════════════════════════════════════════
    // แอดมิน — อนุมัติ
    // ═════════════════════════════════════════════════════════════════

    /**
     * แอดมินยกเลิกและคืนเงินเอง โดยไม่ต้องรอคำขอจากลูกค้า
     *
     * ใช้ตอนที่ร้านเป็นฝ่ายทำไม่ได้ — ของหมดกะทันหัน เตาเสีย ส่งไม่ทัน
     *
     * เบื้องหลังคือ submit() + approve() ต่อกันใน transaction เดียว
     * จึงได้แถวใน tb_cancel_requests กับ tb_refunds เหมือนการยกเลิกปกติ
     * เวลามาตรวจย้อนหลังจะไม่มีเงินออกที่หาที่มาไม่ได้
     *
     * mode เริ่มต้นเป็น full — ร้านทำไม่ได้เอง ไม่ควรไปหักค่าธรรมเนียมจากลูกค้า
     */
    @Transactional
    public Result cancelByAdmin(Long orderId, List<ItemRequest> items, String reasonCode,
            String note, String mode, Double manualAmount, String adminEmail) throws Exception {

        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CancelException("ไม่พบคำสั่งซื้อ"));

        String code = (reasonCode == null || reasonCode.isBlank()) ? "out_of_stock" : reasonCode;
        Long requestId = submit(order.getEmail(), orderId, items, code, note);

        return approve(requestId,
                (mode == null || mode.isBlank()) ? "full" : mode,
                manualAmount, note,
                adminEmail == null ? "admin" : adminEmail);
    }

    /**
     * @param mode         fee_deducted | full | manual
     * @param manualAmount ใช้เมื่อ mode = manual (บาท)
     */
    @Transactional
    public Result approve(Long requestId, String mode, Double manualAmount,
            String note, String adminEmail) throws Exception {

        Map<String, Object> req = loadRequest(requestId);
        if (!STATUS_PENDING.equals(req.get("status"))) {
            throw new CancelException("คำขอนี้ถูกพิจารณาไปแล้ว");
        }

        Long orderId = ((Number) req.get("order_id")).longValue();
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CancelException("ไม่พบคำสั่งซื้อ"));

        boolean full = "full".equals(req.get("scope"));
        List<ResolvedLine> lines = full ? List.of() : loadRequestItems(requestId);
        Double itemsAmount = full ? null
                : lines.stream().mapToDouble(ResolvedLine::amountBaht).sum();

        RefundService.Quote q = refundService.quote(order, itemsAmount);

        // ─── เลือกยอดตามโหมด ───
        long amount;
        long feeDeducted;
        switch (mode == null ? RefundService.MODE_FEE_DEDUCTED : mode) {
            case RefundService.MODE_FULL -> {
                amount = q.fullAmount();
                feeDeducted = 0L;
            }
            case RefundService.MODE_MANUAL -> {
                if (manualAmount == null || manualAmount <= 0) {
                    throw new CancelException("กรุณาระบุยอดที่ต้องการคืน");
                }
                amount = Math.round(manualAmount * 100.0);
                if (amount > q.remaining()) {
                    throw new CancelException(String.format(
                            "ยอดคืนเกินที่เหลือคืนได้ (สูงสุด %.2f บาท)", q.remainingBaht()));
                }
                feeDeducted = Math.max(0, q.gross() - amount);
            }
            default -> {
                amount = q.suggested();
                feeDeducted = q.feeShare();
            }
        }

        // ─── คืนเงินจริง ───
        String reason = "อนุมัติคำขอยกเลิก #" + requestId
                + (note == null || note.isBlank() ? "" : " — " + note);
        refundService.execute(order, requestId, amount, feeDeducted,
                mode == null ? RefundService.MODE_FEE_DEDUCTED : mode, reason, adminEmail);

        // ─── คืน stock + ทำเครื่องหมายรายการที่ยกเลิก ───
        if (full) {
            restoreAll(order);
            order.setOrderStatus("cancelled");
        } else {
            restorePartial(lines);
            // ยอดใน DB ต้องสะท้อนของที่ยังต้องส่งจริง ไม่งั้นใบเสร็จกับรายงานจะโกหก
            recalculateAfterPartialCancel(order);
            // ยังมีของเหลือต้องส่ง → กลับไปสถานะเดิมก่อนยื่นคำขอ
            order.setOrderStatus("confirmed");
        }
        orderRepository.save(order);

        markDecided(requestId, STATUS_APPROVED, adminEmail, note);

        log.info("✅ อนุมัติคำขอ #{} order {} คืน {} บาท (โหมด={})",
                requestId, orderId, StripeService.toBaht(amount), mode);

        return new Result(requestId, orderId, STATUS_APPROVED,
                StripeService.toBaht(amount), StripeService.toBaht(feeDeducted));
    }

    // ═════════════════════════════════════════════════════════════════
    // แอดมิน — ปฏิเสธ
    // ═════════════════════════════════════════════════════════════════

    @Transactional
    public Result reject(Long requestId, String note, String adminEmail) {
        Map<String, Object> req = loadRequest(requestId);
        if (!STATUS_PENDING.equals(req.get("status"))) {
            throw new CancelException("คำขอนี้ถูกพิจารณาไปแล้ว");
        }
        if (note == null || note.isBlank()) {
            throw new CancelException("กรุณาระบุเหตุผลที่ปฏิเสธ เพื่อแจ้งลูกค้า");
        }

        Long orderId = ((Number) req.get("order_id")).longValue();
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CancelException("ไม่พบคำสั่งซื้อ"));

        // คืนสถานะเดิม ของยังถูกจองอยู่เหมือนเดิม ไม่มีอะไรเปลี่ยน
        order.setOrderStatus("confirmed");
        orderRepository.save(order);

        markDecided(requestId, STATUS_REJECTED, adminEmail, note);
        log.info("❌ ปฏิเสธคำขอ #{} order {} — {}", requestId, orderId, note);

        return new Result(requestId, orderId, STATUS_REJECTED, 0.0, 0.0);
    }

    // ═════════════════════════════════════════════════════════════════
    // อ่านข้อมูล
    // ═════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> listByStatus(String status) {
        String sql = "SELECT r.*, o.ord_code, o.total AS order_total " +
                "FROM tb_cancel_requests r JOIN tb_orders o ON o.id = r.order_id " +
                (status == null || status.isBlank() ? "" : "WHERE r.status = ? ") +
                "ORDER BY r.created_at DESC";
        return (status == null || status.isBlank())
                ? jdbcTemplate.queryForList(sql)
                : jdbcTemplate.queryForList(sql, status);
    }

    public List<Map<String, Object>> listByEmail(String email) {
        return jdbcTemplate.queryForList(
                "SELECT r.*, o.ord_code FROM tb_cancel_requests r " +
                        "JOIN tb_orders o ON o.id = r.order_id " +
                        "WHERE r.email = ? ORDER BY r.created_at DESC",
                email);
    }

    public List<Map<String, Object>> itemsOf(Long requestId) {
        return jdbcTemplate.queryForList(
                "SELECT ci.*, oi.product_name, oi.selected_option, oi.price " +
                        "FROM tb_cancel_request_items ci " +
                        "JOIN tb_order_items oi ON oi.id = ci.order_item_id " +
                        "WHERE ci.request_id = ?",
                requestId);
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    private OrderEntity requireOwnedOrder(String email, Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CancelException("ไม่พบคำสั่งซื้อ"));
        if (!email.equals(order.getEmail())) {
            throw new CancelException("ไม่พบคำสั่งซื้อ"); // ไม่บอกว่าเป็นของคนอื่น
        }
        return order;
    }

    private void requireCancellable(OrderEntity order) {
        if (!"paid".equals(order.getPaymentStatus())) {
            throw new CancelException("คำสั่งซื้อนี้ยังไม่ได้ชำระเงิน ไม่ต้องขอคืนเงิน");
        }
        String st = order.getOrderStatus();
        if (ORDER_STATUS_REQUESTED.equals(st)) {
            throw new CancelException("มีคำขอยกเลิกที่รอการพิจารณาอยู่แล้ว");
        }
        if (!CANCELLABLE_STATUSES.contains(st)) {
            throw new CancelException(
                    "คำสั่งซื้ออยู่ในขั้นตอน \"" + st + "\" แล้ว ไม่สามารถขอยกเลิกได้ กรุณาติดต่อร้านโดยตรง");
        }
    }

    private boolean hasPendingRequest(Long orderId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_cancel_requests WHERE order_id = ? AND status = 'pending'",
                Integer.class, orderId);
        return n != null && n > 0;
    }

    /** ตรวจรายการที่ลูกค้าขอยกเลิก แล้วคำนวณราคารวม */
    private Resolved sumRequestedItems(OrderEntity order, List<ItemRequest> requests) {
        List<OrderItemEntity> orderItems = orderItemRepository.findByOrderId(order.getId());
        Map<Long, OrderItemEntity> byId = new HashMap<>();
        for (OrderItemEntity oi : orderItems)
            byId.put(oi.getId(), oi);

        List<ResolvedLine> lines = new ArrayList<>();
        for (ItemRequest r : requests) {
            OrderItemEntity oi = byId.get(r.orderItemId());
            if (oi == null) {
                throw new CancelException("รายการสินค้าไม่ตรงกับคำสั่งซื้อนี้");
            }

            int alreadyCancelled = cancelledQtyOf(oi.getId());
            int available = oi.getQuantity() - alreadyCancelled;
            if (r.quantity() <= 0 || r.quantity() > available) {
                throw new CancelException(
                        "จำนวนที่ขอยกเลิกของ \"" + oi.getProductName() + "\" ไม่ถูกต้อง");
            }

            // เค้กขายเป็นปอนด์ ยกเลิกครึ่งปอนด์ไม่ได้ — ต้องหารลงตัวกับจำนวนชิ้นต่อออเดอร์
            // ถ้าไม่เช็ค toDisplayQty จะปัดลงเป็น 0 แล้วคืนเงิน 0 บาทโดยไม่มีใครรู้เหตุ
            int multiplier = qtyHelper.getMultiplier(oi.getSelectedOption());
            if (r.quantity() % multiplier != 0) {
                throw new CancelException(
                        "จำนวนที่ขอยกเลิกของ \"" + oi.getProductName()
                                + "\" ไม่ตรงกับขนาดที่สั่ง ต้องยกเลิกทั้งชิ้นเท่านั้น");
            }

            // quantity เก็บเป็น "ชิ้น" ต้องแปลงเป็นจำนวนออเดอร์ก่อนคูณราคา
            int displayQty = qtyHelper.toDisplayQty(r.quantity(), oi.getSelectedOption());
            double amount = round2(oi.getPrice() * displayQty);

            lines.add(new ResolvedLine(oi.getId(), oi.getProductId(),
                    r.quantity(), amount, oi.getProductName()));
        }

        if (lines.isEmpty()) {
            throw new CancelException("กรุณาเลือกรายการที่ต้องการยกเลิก");
        }
        return new Resolved(lines);
    }

    private int cancelledQtyOf(Long orderItemId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COALESCE(cancelled_qty, 0) FROM tb_order_items WHERE id = ?",
                Integer.class, orderItemId);
        return n == null ? 0 : n;
    }

    private void restoreAll(OrderEntity order) {
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(order.getId());
        List<StockService.StockChange> changes = new ArrayList<>();
        for (OrderItemEntity oi : items) {
            int remaining = oi.getQuantity() - cancelledQtyOf(oi.getId());
            if (remaining <= 0)
                continue;
            changes.add(new StockService.StockChange(oi.getProductId(), remaining));
            jdbcTemplate.update(
                    "UPDATE tb_order_items SET cancelled_qty = quantity WHERE id = ?", oi.getId());
        }
        if (!changes.isEmpty())
            stockService.batchIncreaseStock(changes);
    }

    /**
     * คำนวณ subtotal/total ใหม่จากของที่ยังไม่ถูกยกเลิก
     *
     * เรียกหลัง restorePartial เท่านั้น — cancelled_qty ต้องถูกอัพเดตก่อน
     *
     * ค่าส่งไม่คิดใหม่ — ของยังต้องส่งอยู่ดี และเราไม่คืนค่าส่งตอนยกเลิกบางรายการอยู่แล้ว
     * ถ้าคิดใหม่ตามขั้นบันได ยอดสินค้าที่ลดลงอาจทำให้ค่าส่งแพงขึ้น กลายเป็นเก็บเงินเพิ่มทีหลัง
     */
    private void recalculateAfterPartialCancel(OrderEntity order) {
        double subtotal = 0.0;
        for (OrderItemEntity oi : orderItemRepository.findByOrderId(order.getId())) {
            int remaining = oi.getQuantity() - cancelledQtyOf(oi.getId());
            if (remaining <= 0)
                continue;
            subtotal += oi.getPrice() * qtyHelper.toDisplayQty(remaining, oi.getSelectedOption());
        }
        double shipping = order.getShipping() == null ? 0.0 : order.getShipping();

        order.setSubtotal(round2(subtotal));
        order.setTotal(round2(subtotal + shipping));

        log.info("ปรับยอด order {} เหลือ subtotal {} total {}",
                order.getId(), order.getSubtotal(), order.getTotal());
    }

    private void restorePartial(List<ResolvedLine> lines) {
        List<StockService.StockChange> changes = new ArrayList<>();
        for (ResolvedLine l : lines) {
            changes.add(new StockService.StockChange(l.productId(), l.rawQuantity()));
            jdbcTemplate.update(
                    "UPDATE tb_order_items SET cancelled_qty = COALESCE(cancelled_qty, 0) + ? WHERE id = ?",
                    l.rawQuantity(), l.orderItemId());
        }
        if (!changes.isEmpty())
            stockService.batchIncreaseStock(changes);
    }

    private Map<String, Object> loadRequest(Long requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tb_cancel_requests WHERE id = ?", requestId);
        if (rows.isEmpty())
            throw new CancelException("ไม่พบคำขอยกเลิก");
        return rows.get(0);
    }

    private List<ResolvedLine> loadRequestItems(Long requestId) {
        return jdbcTemplate.query(
                "SELECT ci.order_item_id, ci.quantity, ci.line_amount, " +
                        "       oi.product_id, oi.product_name " +
                        "FROM tb_cancel_request_items ci " +
                        "JOIN tb_order_items oi ON oi.id = ci.order_item_id " +
                        "WHERE ci.request_id = ?",
                (rs, i) -> new ResolvedLine(
                        rs.getLong("order_item_id"),
                        rs.getLong("product_id"),
                        rs.getInt("quantity"),
                        rs.getDouble("line_amount"),
                        rs.getString("product_name")),
                requestId);
    }

    private void markDecided(Long requestId, String status, String adminEmail, String note) {
        jdbcTemplate.update(
                "UPDATE tb_cancel_requests SET status = ?, decided_by = ?, " +
                        "decided_at = NOW(), decision_note = ? WHERE id = ?",
                status, adminEmail, note, requestId);
    }

    private static Long extractId(KeyHolder keys) {
        Map<String, Object> k = keys.getKeys();
        if (k == null || k.get("id") == null) {
            Number n = keys.getKey();
            if (n == null)
                throw new CancelException("บันทึกคำขอไม่สำเร็จ");
            return n.longValue();
        }
        return ((Number) k.get("id")).longValue();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ═════════════════════════════════════════════════════════════════
    // DTOs
    // ═════════════════════════════════════════════════════════════════

    public record ItemRequest(Long orderItemId, int quantity) {
    }

    public record Preview(String scope, double grossAmount, double feeDeducted,
            double refundAmount, boolean includesShipping) {
    }

    public record Result(Long requestId, Long orderId, String status,
            double refundAmount, double feeDeducted) {
    }

    private record ResolvedLine(Long orderItemId, Long productId, int rawQuantity,
            double amountBaht, String productName) {
    }

    private record Resolved(List<ResolvedLine> lines) {
        double totalBaht() {
            return lines.stream().mapToDouble(ResolvedLine::amountBaht).sum();
        }
    }

    public static class CancelException extends RuntimeException {
        public CancelException(String message) {
            super(message);
        }
    }

    /** ห่อ helper เดิมไว้ เผื่ออนาคตเปลี่ยนวิธีคำนวณจำนวน */
    private record ProductQuantityHelperAdapter(
            com.app.my_project.common.ProductQuantityHelper inner) {
        int toDisplayQty(int rawQty, String selectedOption) {
            return inner.toDisplayQty(rawQty, selectedOption);
        }

        /** จำนวนชิ้นต่อ 1 ออเดอร์ — เค้ก 1 ปอนด์ = 8, 2 ปอนด์ = 16 */
        int getMultiplier(String selectedOption) {
            return inner.getMultiplier(selectedOption);
        }
    }
}