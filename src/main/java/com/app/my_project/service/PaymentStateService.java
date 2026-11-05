package com.app.my_project.service;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PaymentStateService — เปลี่ยนสถานะการจ่ายเงินของ order
 *
 * ═══ กฎเหล็ก ═══
 * มีที่เดียวที่ตั้ง paymentStatus = "paid" ได้ คือ webhook ของ Stripe
 * การที่ frontend เห็นว่า confirmPayment() สำเร็จ *ห้าม* ใช้ยืนยันการจ่ายเงิน
 * เพราะ JavaScript ถูกแก้ได้จากฝั่งลูกค้า
 *
 * ═══ สถานะที่ใช้ ═══
 * awaiting_payment → paid | failed | expired
 */
@Service
public class PaymentStateService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateService.class);

    public static final String AWAITING = "awaiting_payment";
    public static final String PAID = "paid";
    public static final String FAILED = "failed";
    public static final String EXPIRED = "expired";

    /**
     * แถวที่ยังไม่จ่ายจะถูกเขียนทับได้เฉพาะที่สร้างไว้ไม่เกินกี่นาที
     *
     * ตรงกับเวลาที่ QR หมดอายุ — เกินจากนี้ถือว่าคนละรอบการซื้อ ไม่ใช่การกดจ่ายซ้ำ
     * ถ้าไม่จำกัดเวลา การซื้อวันนี้จะไปเขียนทับแถวที่ทิ้งไว้เมื่อวาน
     * แล้วเลข order จะเดินถอยหลัง ไล่ดูตามลำดับเวลาไม่ได้
     */
    private static final int REUSE_WINDOW_MINUTES = 30;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockService stockService;
    private final JdbcTemplate jdbcTemplate;

    public PaymentStateService(OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            StockService stockService,
            JdbcTemplate jdbcTemplate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockService = stockService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ═════════════════════════════════════════════════════════════════
    // IDEMPOTENCY
    // ═════════════════════════════════════════════════════════════════

    /**
     * บันทึก event id ลง tb_stripe_events
     *
     * Stripe รับประกันแค่ "at least once" — event เดิมอาจมาซ้ำได้
     * ถ้าไม่กัน จะคืน stock ซ้ำ/ส่งอีเมลซ้ำ
     *
     * @return true = event ใหม่ ให้ประมวลผลต่อ, false = เคยเห็นแล้ว ข้ามไป
     */
    @Transactional
    public boolean claimEvent(String eventId, String eventType) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO tb_stripe_events (event_id, event_type) VALUES (?, ?) " +
                        "ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType);
        if (inserted == 0) {
            log.info("ข้าม event ซ้ำ {} ({})", eventId, eventType);
        }
        return inserted > 0;
    }

    // ═════════════════════════════════════════════════════════════════
    // STATE TRANSITIONS
    // ═════════════════════════════════════════════════════════════════

    /**
     * จ่ายเงินสำเร็จ → paid + เคลียร์ตะกร้าของลูกค้า
     *
     * stock ถูกตัดไปแล้วตั้งแต่ตอนสร้าง order (จอง) จึงไม่ต้องตัดซ้ำที่นี่
     */
    @Transactional
    public void markPaid(Long orderId, String paymentIntentId) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            log.error("webhook อ้างถึง order {} ที่ไม่มีอยู่จริง", orderId);
            return;
        }

        OrderEntity order = opt.get();
        if (PAID.equals(order.getPaymentStatus())) {
            log.info("order {} เป็น paid อยู่แล้ว ข้าม", orderId);
            return;
        }

        order.setPaymentStatus(PAID);
        order.setPaymentId(paymentIntentId);
        order.setStripePaymentIntentId(paymentIntentId);
        // ถ้า order เคยถูกยกเลิกเพราะหมดเวลา แล้วเงินเข้าทีหลัง → ปลุกกลับมา
        if ("cancelled".equals(order.getOrderStatus())) {
            order.setOrderStatus("pending");
        }
        orderRepository.save(order);

        clearCart(order.getEmail());
        closeDineInOrders(orderId);
        log.info("✅ order {} ({}) จ่ายเงินสำเร็จ", orderId, order.getOrdCode());
    }

    /**
     * จ่ายไม่สำเร็จ / หมดเวลา → คืน stock ที่จองไว้ + ปิด order
     *
     * @param newPaymentStatus FAILED หรือ EXPIRED
     */
    @Transactional
    public void releaseOrder(Long orderId, String newPaymentStatus, String reason) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty())
            return;

        OrderEntity order = opt.get();

        // จ่ายไปแล้วก็อย่าไปยุ่ง (เช่น event มาสลับลำดับ)
        if (PAID.equals(order.getPaymentStatus())) {
            log.warn("order {} จ่ายแล้ว ไม่ปล่อยของคืน (reason={})", orderId, reason);
            return;
        }
        // ปล่อยไปแล้วรอบหนึ่ง อย่าคืน stock ซ้ำ
        if (FAILED.equals(order.getPaymentStatus()) || EXPIRED.equals(order.getPaymentStatus())) {
            return;
        }

        restoreStock(orderId);

        order.setPaymentStatus(newPaymentStatus);
        order.setOrderStatus("cancelled");
        order.setNote(appendNote(order.getNote(), reason));
        orderRepository.save(order);

        log.info("↩️ order {} ปล่อยคืน ({}): {}", orderId, newPaymentStatus, reason);
    }

    /**
     * เตรียม order สำหรับการกดจ่ายเงินรอบใหม่ของลูกค้าคนเดิม
     *
     * ═══ กติกาเดียวของเมธอดนี้ ═══
     * ลูกค้าหนึ่งคนมีแถวที่ยังไม่จ่ายได้ "แถวเดียว" เท่านั้น
     * ทุกครั้งที่กดจ่ายเงินใหม่ ให้เขียนทับแถวเดิม ไม่สร้างแถวใหม่
     *
     * เดิมทีทุกครั้งที่กดจ่ายจะได้ id ใหม่ ลูกค้าที่สลับบัตร → QR หรือกดย้อนกลับ
     * จึงกินเลข order ไปหลายใบทั้งที่ซื้อครั้งเดียว
     *
     * ═══ ทำอะไรบ้าง ═══
     * 1. หาแถวที่ยังไม่จ่ายของ email นี้ (awaiting / expired / failed) ที่สร้างไม่เกิน 30 นาที
     * 2. เอาแถวล่าสุดมาใช้ต่อ ที่เหลือปล่อยคืน stock แล้วปิดทิ้ง
     * 3. คืน stock ที่แถวนั้นจองไว้ (ถ้ายังจองอยู่) → ลบ item เดิม → เขียน item ใหม่ตามตะกร้า → จอง stock ใหม่
     * 4. รีเซ็ตยอด สถานะ และ intent เก่าให้เป็นใบรอจ่ายที่สะอาด
     *
     * ord_code กับ id คงเดิม ลูกค้าจึงเห็นเลขเดียวตลอดจนกว่าจะจ่ายสำเร็จ
     * เมื่อจ่ายแล้วแถวนั้นไม่เข้าเงื่อนไขอีก การซื้อครั้งถัดไปจึงได้แถวใหม่ตามปกติ
     *
     * @return แถวที่เขียนทับแล้ว (null = ไม่มีแถวค้าง ให้ไปสร้างใหม่) + intent ที่ต้องยกเลิกกับ Stripe
     */
    @Transactional
    public ReuseResult prepareForNewIntent(String email, List<CartLine> wanted,
            double subtotal, double shipping, double total) {

        List<OrderEntity> unpaid = new ArrayList<>();
        unpaid.addAll(orderRepository.findByEmailAndPaymentStatus(email, AWAITING));
        unpaid.addAll(orderRepository.findByEmailAndPaymentStatus(email, EXPIRED));
        unpaid.addAll(orderRepository.findByEmailAndPaymentStatus(email, FAILED));

        List<String> intentsToCancel = new ArrayList<>();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(REUSE_WINDOW_MINUTES);

        List<OrderEntity> open = new ArrayList<>();
        for (OrderEntity o : unpaid) {
            if (o.getCreatedAt() != null && o.getCreatedAt().isAfter(cutoff)) {
                open.add(o);
            } else if (AWAITING.equals(o.getPaymentStatus())) {
                // ค้างเกินเวลาแล้ว ปล่อยของคืนแต่ไม่เอามาเขียนทับ
                restoreStock(o.getId());
                o.setPaymentStatus(EXPIRED);
                o.setOrderStatus("cancelled");
                o.setNote(appendNote(o.getNote(), "ยกเลิกอัตโนมัติ: เกินเวลาชำระเงิน"));
                orderRepository.save(o);
                addIntent(intentsToCancel, o);
            }
        }

        if (open.isEmpty()) {
            return new ReuseResult(null, intentsToCancel);
        }

        open.sort(Comparator.comparing(OrderEntity::getId).reversed());
        OrderEntity keep = open.get(0);

        // แถวค้างอื่นๆ ปิดทิ้งให้หมด จะได้เหลือใบเดียวจริงๆ
        for (int i = 1; i < open.size(); i++) {
            OrderEntity other = open.get(i);
            if (AWAITING.equals(other.getPaymentStatus())) {
                restoreStock(other.getId());
                other.setPaymentStatus(EXPIRED);
                other.setOrderStatus("cancelled");
                other.setNote(appendNote(other.getNote(), "ยกเลิกอัตโนมัติ: ลูกค้าเริ่มการชำระเงินใหม่"));
                orderRepository.save(other);
                log.info("ปิด order ค้าง {} ของ {}", other.getId(), email);
            }
            addIntent(intentsToCancel, other);
        }

        // intent เก่าของแถวที่จะใช้ต่อก็ต้องตาย ไม่งั้นลูกค้ามี QR จ่ายได้สองใบ
        addIntent(intentsToCancel, keep);

        // ── เขียนรายการใหม่ทับของเดิม ──
        if (AWAITING.equals(keep.getPaymentStatus())) {
            restoreStock(keep.getId()); // ยังจองอยู่ คืนก่อนแล้วค่อยจองตามตะกร้าใหม่
        }
        orderItemRepository.deleteByOrderId(keep.getId());

        for (CartLine line : wanted) {
            OrderItemEntity item = new OrderItemEntity();
            item.setOrderId(keep.getId());
            item.setProductId(line.productId());
            item.setProductName(line.productName());
            item.setPrice(line.unitPrice());
            item.setQuantity(line.rawQuantity());
            item.setSelectedOption(line.selectedOption());
            item.setImage(line.image());
            item.setCancelledQty(0);
            orderItemRepository.save(item);

            stockService.decreaseStock(line.productId(), line.rawQuantity());
        }

        keep.setSubtotal(subtotal);
        keep.setShipping(shipping);
        keep.setTotal(total);
        keep.setPaymentStatus(AWAITING);
        keep.setOrderStatus("pending");
        keep.setStripePaymentIntentId(null);
        keep.setPaymentId(null);
        keep.setSlipImage(null);
        keep.setNote(null);
        keep.setCreatedAt(LocalDateTime.now());
        orderRepository.save(keep);

        log.info("ใช้ order {} ({}) ต่อสำหรับการจ่ายเงินรอบใหม่ของ {}",
                keep.getId(), keep.getOrdCode(), email);

        return new ReuseResult(keep, intentsToCancel);
    }

    private void addIntent(List<String> bucket, OrderEntity order) {
        String id = order.getStripePaymentIntentId();
        if (id != null && !id.isBlank()) {
            bucket.add(id);
        }
    }

    /** หนึ่งบรรทัดในตะกร้าที่คิดราคาแล้ว — quantity เป็น "ชิ้น" เหมือนใน tb_order_items */
    public record CartLine(Long productId, String productName, String selectedOption,
            int rawQuantity, double unitPrice, String image) {}

    public record ReuseResult(OrderEntity reusable, List<String> intentsToCancel) {}

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    /**
     * บิลหน้าร้านจ่ายแล้ว → ปิดออเดอร์ของโต๊ะที่ถูกรวมเข้ามาด้วย
     *
     * ออเดอร์ออนไลน์จะไม่มีแถวไหนตรง คำสั่งนี้จึงไม่ทำอะไรเลย ปล่อยให้วิ่งได้ทุกครั้ง
     */
    private void closeDineInOrders(Long billOrderId) {
        try {
            int n = jdbcTemplate.update(
                    "UPDATE tb_dinein_orders SET payment_status = 'paid' WHERE bill_order_id = ?",
                    billOrderId);
            if (n > 0) {
                log.info("ปิดออเดอร์หน้าร้าน {} รายการของบิล {}", n, billOrderId);
            }
        } catch (Exception e) {
            // พลาดตรงนี้ไม่ควรทำให้ webhook fail แล้ว Stripe ยิงซ้ำ
            log.error("ปิดออเดอร์หน้าร้านของบิล {} ไม่สำเร็จ", billOrderId, e);
        }
    }

    /**
     * บิลหน้าร้านถูกปล่อยคืน → ปลดล็อกออเดอร์โต๊ะให้กดเรียกเก็บใหม่ได้
     */
    private void unlinkDineInOrders(Long billOrderId) {
        try {
            // เก็บ bill_order_id ไว้ ล้างแต่สถานะจ่ายเงิน
            // ถ้าล้างเส้นเชื่อมทิ้ง ตอนกดเรียกบิลใหม่จะหาแถวเดิมไม่เจอแล้วสร้างแถวใหม่
            jdbcTemplate.update(
                    "UPDATE tb_dinein_orders SET payment_status = NULL " +
                            "WHERE bill_order_id = ? AND payment_status <> 'paid'",
                    billOrderId);
        } catch (Exception e) {
            log.error("ปลดล็อกออเดอร์หน้าร้านของบิล {} ไม่สำเร็จ", billOrderId, e);
        }
    }

    /**
     * คืน stock ที่จองไว้
     *
     * ⚠️ บิลหน้าร้านข้ามขั้นตอนนี้ — ของถูกตัด stock ไปตอนลูกค้าสั่งที่โต๊ะแล้ว
     * และกินไปเรียบร้อย การคืน stock ตอนบิลหมดอายุจะทำให้ของงอกขึ้นมาเอง
     */
    private void restoreStock(Long orderId) {
        if (isDineInBill(orderId)) {
            log.info("order {} เป็นบิลหน้าร้าน ไม่คืน stock", orderId);
            unlinkDineInOrders(orderId);
            return;
        }
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty())
            return;
        List<StockService.StockChange> changes = items.stream()
                .map(i -> new StockService.StockChange(i.getProductId(), i.getQuantity()))
                .toList();
        stockService.batchIncreaseStock(changes);
    }

    private boolean isDineInBill(Long orderId) {
        return orderRepository.findById(orderId)
                .map(o -> "dine-in".equals(o.getOrderType()))
                .orElse(false);
    }

    private void clearCart(String email) {
        try {
            jdbcTemplate.update("DELETE FROM tb_cart WHERE email = ?", email);
        } catch (Exception e) {
            // ตะกร้าเคลียร์ไม่ได้ไม่ควรทำให้ webhook fail แล้ว Stripe ยิงซ้ำ
            log.error("เคลียร์ตะกร้าของ {} ไม่สำเร็จ", email, e);
        }
    }

    private static String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank())
            return existing;
        if (existing == null || existing.isBlank())
            return "[ระบบ] " + addition;
        return existing + "\n[ระบบ] " + addition;
    }
}