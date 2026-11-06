package com.app.my_project.service;

import com.app.my_project.common.CartMode;
import com.app.my_project.common.CheckoutMethod;
import com.app.my_project.common.OrderCodeGenerator;
import com.app.my_project.common.ProductQuantityHelper;
import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * ทานในร้าน: สั่งอาหารเข้าครัว และออกบิลเรียกเก็บเงิน
 *
 * ═══ ราคาคิดที่ server เท่านั้น ═══
 * ออเดอร์โต๊ะคิดจากตะกร้าทานในร้าน (tb_cart.cart_mode = dinein) ผ่าน PricingService
 * บุฟเฟต์คิดจากราคาต่อคนของร้าน × จำนวนคน — ไม่รับตัวเลขราคาจาก browser เลย
 * เพราะยอดบิลถูกนำไปตัดบัตร / ออก QR จริง
 *
 * ═══ บิลกับออเดอร์โต๊ะ ═══
 * บิล = หนึ่งแถวใน tb_orders (order_type = dine-in) ที่รวมออเดอร์โต๊ะที่เสิร์ฟแล้ว (bill_order_id ชี้มาที่บิล)
 * บิลที่ไม่ได้เงินถูกลบทิ้ง แล้วออเดอร์โต๊ะถูกปลดให้เรียกบิลใหม่ได้ (PaymentStateService.discardUnpaid)
 */
@Service
public class DineInBillService {

    private static final Logger log = LoggerFactory.getLogger(DineInBillService.class);

    /** ราคาบุฟเฟต์ต่อคน (บาท) — ต้องตรงกับที่หน้า order-mode แสดง */
    public static final double BUFFET_PRICE_PER_PERSON = 1599.0;
    /** โต๊ะใหญ่สุดของร้านรับได้ 8 คน */
    public static final int MAX_BUFFET_PAX = 8;
    private static final int MAX_TABLE_NO_LENGTH = 10;

    private static final String DINE_IN = "dine-in";
    private static final String SERVED = "completed";

    public enum DineType {
        ALACARTE("alacarte"), BUFFET("buffet");

        private final String value;

        DineType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** ไม่ส่งมา / ไม่รู้จัก = à la carte */
        public static DineType parse(String raw) {
            return BUFFET.value.equalsIgnoreCase(raw == null ? "" : raw.trim()) ? BUFFET : ALACARTE;
        }
    }

    /** คำขอที่ทำต่อไม่ได้ — status คือ HTTP status ที่ควรตอบ */
    public static class BillException extends RuntimeException {
        private final HttpStatus status;

        public BillException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final DineInOrderRepository dineInOrderRepository;
    private final DineInOrderItemRepository dineInOrderItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PricingService pricingService;
    private final StockService stockService;
    private final CartService cartService;
    private final PaymentStateService paymentStateService;
    private final PaymentReconciler paymentReconciler;
    private final OrderCodeGenerator codeGenerator;
    private final ProductQuantityHelper qtyHelper;

    public DineInBillService(DineInOrderRepository dineInOrderRepository,
            DineInOrderItemRepository dineInOrderItemRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PricingService pricingService,
            StockService stockService,
            CartService cartService,
            PaymentStateService paymentStateService,
            PaymentReconciler paymentReconciler,
            OrderCodeGenerator codeGenerator,
            ProductQuantityHelper qtyHelper) {
        this.dineInOrderRepository = dineInOrderRepository;
        this.dineInOrderItemRepository = dineInOrderItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.pricingService = pricingService;
        this.stockService = stockService;
        this.cartService = cartService;
        this.paymentStateService = paymentStateService;
        this.paymentReconciler = paymentReconciler;
        this.codeGenerator = codeGenerator;
        this.qtyHelper = qtyHelper;
    }

    // ═════════════════════════════════════════════════════════════════
    // สั่งอาหารเข้าครัว
    // ═════════════════════════════════════════════════════════════════

    /**
     * ส่งตะกร้าทานในร้านเข้าครัว → ออเดอร์โต๊ะใหม่ 1 ใบ + ตัด stock + ล้างตะกร้าทานในร้าน
     *
     * @throws BillException ไม่ระบุโต๊ะ
     * @throws PricingService.CartValidationException ตะกร้าว่าง / ของหมด / ปิดขาย
     */
    @Transactional
    public DineInOrderEntity placeTableOrder(String email, String tableNo) {
        String table = tableNo == null ? "" : tableNo.trim();
        if (table.isEmpty() || table.length() > MAX_TABLE_NO_LENGTH)
            throw new BillException(HttpStatus.BAD_REQUEST, "กรุณาระบุเลขโต๊ะ");

        PricingService.PricedCart cart = pricingService.priceCart(email, CartMode.DINE_IN);

        DineInOrderEntity order = new DineInOrderEntity();
        order.setEmail(email);
        order.setTableNo(table);
        order.setSubtotal(cart.subtotal());
        order.setTotal(cart.subtotal());
        order.setOrderStatus("pending");
        order.setCreatedAt(LocalDateTime.now());
        DineInOrderEntity saved = dineInOrderRepository.save(order);
        saved.setOrdCode(codeGenerator.generate(saved.getId()));
        dineInOrderRepository.save(saved);

        for (PricingService.PricedLine line : cart.lines()) {
            DineInOrderItemEntity item = new DineInOrderItemEntity();
            item.setOrderId(saved.getId());
            item.setProductId(line.productId());
            item.setProductName(line.productName());
            item.setPrice(line.unitPrice());
            item.setQuantity(line.displayQuantity()); // ออเดอร์โต๊ะเก็บเป็น "ออเดอร์" (บิลแปลงเป็น "ชิ้น" ตอนคัดลอก)
            item.setSelectedOption(line.selectedOption());
            item.setImage(line.image());
            dineInOrderItemRepository.save(item);

            stockService.decreaseStock(line.productId(), line.rawQuantity());
        }

        cartService.clear(email, CartMode.DINE_IN);
        log.info("🍳 โต๊ะ {} สั่ง {} ({} รายการ, ฿{})", table, saved.getOrdCode(), cart.lines().size(), cart.subtotal());
        return saved;
    }

    // ═════════════════════════════════════════════════════════════════
    // เรียกเก็บบิล
    // ═════════════════════════════════════════════════════════════════

    /**
     * เตรียมบิลรอจ่าย (awaiting_payment) ของออเดอร์โต๊ะที่เสิร์ฟแล้ว — ยังไม่ออกช่องทางจ่ายเงิน
     *
     * กดเรียกบิลซ้ำ (เช่น สลับวิธีจ่าย) ใช้แถวบิลเดิมเขียนทับ เลขบิลจึงไม่เปลี่ยน
     *
     * @param tableNo null/ว่าง = ทุกโต๊ะของลูกค้าคนนี้
     * @param pax     จำนวนคน (เฉพาะบุฟเฟต์)
     * @throws BillException ไม่มีออเดอร์ให้เก็บเงิน / จำนวนคนผิด / มีรอบจ่ายเงินที่ยังไม่จบ
     */
    @Transactional
    public OrderEntity prepareBill(String email, String tableNo, DineType dineType, int pax, CheckoutMethod method) {
        if (dineType == DineType.BUFFET && (pax < 1 || pax > MAX_BUFFET_PAX))
            throw new BillException(HttpStatus.BAD_REQUEST, "จำนวนคนต้องอยู่ระหว่าง 1-" + MAX_BUFFET_PAX + " คน");

        // ปิดรอบจ่ายเงินเก่าก่อน — บิลที่จ่ายแล้วแต่ webhook ยังไม่มาจะกลายเป็น paid และไม่ถูกเรียกเก็บซ้ำ
        Set<Long> locked = paymentReconciler.settleAwaiting(email, DINE_IN);
        rejectIfPaymentInProgress(email, locked);

        List<DineInOrderEntity> served = activeTableOrders(email, tableNo).stream()
                .filter(o -> SERVED.equals(o.getOrderStatus()))
                .toList();
        if (served.isEmpty())
            throw new BillException(HttpStatus.BAD_REQUEST, "ไม่มีออเดอร์ที่เสร็จแล้วสำหรับเรียกเก็บเงิน");

        OrderEntity bill = reusableBill(email, locked);
        double total = dineType == DineType.BUFFET
                ? round2(BUFFET_PRICE_PER_PERSON * pax)
                : round2(served.stream().mapToDouble(DineInOrderEntity::getTotal).sum());

        String table = served.get(0).getTableNo();
        bill.setEmail(email);
        bill.setSubtotal(total);
        bill.setShipping(0.0);
        bill.setTotal(total);
        bill.setPaymentMethod(method.storedValue());
        bill.setPaymentStatus(PaymentStateService.AWAITING); // paid ได้จาก Stripe หรือแอดมินยืนยันยอดโอนเท่านั้น
        bill.setOrderType(DINE_IN);
        bill.setOrderStatus("pending");
        bill.setDineType(dineType.value());
        bill.setBuffetPax(dineType == DineType.BUFFET ? pax : null);
        bill.setBuffetPricePerPerson(dineType == DineType.BUFFET ? BUFFET_PRICE_PER_PERSON : null);
        bill.setNote("โต๊ะ " + table + (dineType == DineType.BUFFET ? " | Buffet " + pax + " คน" : ""));
        bill.setStripePaymentIntentId(null);
        bill.setPaymentId(null);
        bill.setSlipImage(null);
        bill.setCreatedAt(LocalDateTime.now());
        OrderEntity saved = orderRepository.save(bill);
        if (saved.getOrdCode() == null || saved.getOrdCode().isBlank()) {
            saved.setOrdCode(codeGenerator.generate(saved.getId()));
            saved = orderRepository.save(saved);
        }

        linkServedOrders(saved, served);
        log.info("🧾 บิล {} โต๊ะ {} ยอด ฿{} ({}, {})", saved.getOrdCode(), table, total,
                dineType.value(), method.requestValue());
        return saved;
    }

    /** ออเดอร์โต๊ะที่ยังไม่ถูกยกเลิกและยังไม่จ่าย */
    public List<DineInOrderEntity> activeTableOrders(String email, String tableNo) {
        boolean anyTable = tableNo == null || tableNo.isBlank();
        return dineInOrderRepository.findByEmailOrderByCreatedAtDesc(email).stream()
                .filter(o -> anyTable || tableNo.equals(o.getTableNo()))
                .filter(o -> !"cancelled".equals(o.getOrderStatus()) && !"paid".equals(o.getPaymentStatus()))
                .toList();
    }

    /**
     * ห้ามออกบิลซ้อนกับรอบที่ยังไม่จบ
     *  - แจ้งโอนแล้ว รอแอดมินตรวจยอด → ถ้าออกบิลใหม่ ลูกค้าอาจจ่ายสองรอบ
     *  - กำลังตัดเงิน / ถาม Stripe ไม่สำเร็จ (locked และยังรอจ่าย)
     */
    private void rejectIfPaymentInProgress(String email, Set<Long> locked) {
        boolean verifying = orderRepository.findByEmailAndPaymentStatus(email, PaymentStateService.VERIFYING)
                .stream().anyMatch(o -> DINE_IN.equals(o.getOrderType()));
        if (verifying)
            throw new BillException(HttpStatus.CONFLICT, "แจ้งโอนแล้ว กรุณารอร้านตรวจสอบยอด");

        boolean inFlight = orderRepository.findByEmailAndPaymentStatus(email, PaymentStateService.AWAITING)
                .stream().anyMatch(o -> DINE_IN.equals(o.getOrderType()) && locked.contains(o.getId()));
        if (inFlight)
            throw new BillException(HttpStatus.CONFLICT, "กำลังตรวจสอบการชำระเงินรอบก่อน กรุณาลองใหม่อีกครั้ง");
    }

    /**
     * บิลรอจ่ายแถวล่าสุดของลูกค้า (intent ถูกยกเลิกแล้วใน settleAwaiting) เอามาเขียนทับ
     * แถวเกินมาลบทิ้ง — ลูกค้าหนึ่งคนมีบิลรอจ่ายได้ใบเดียว
     */
    private OrderEntity reusableBill(String email, Set<Long> locked) {
        List<OrderEntity> open = orderRepository.findByEmailAndPaymentStatus(email, PaymentStateService.AWAITING)
                .stream()
                .filter(o -> DINE_IN.equals(o.getOrderType()) && !locked.contains(o.getId()))
                .sorted(Comparator.comparing(OrderEntity::getId).reversed())
                .toList();
        if (open.isEmpty())
            return new OrderEntity();

        open.stream().skip(1).forEach(o -> paymentStateService.discardUnpaid(o.getId(), "ลูกค้าเรียกบิลใหม่"));
        OrderEntity keep = open.get(0);
        orderItemRepository.deleteByOrderId(keep.getId());
        return keep;
    }

    /** ผูกออเดอร์โต๊ะเข้ากับบิล แล้วคัดลอกรายการไป tb_order_items (ให้หน้าแอดมิน/ใบเสร็จใช้ได้เหมือนออนไลน์) */
    private void linkServedOrders(OrderEntity bill, List<DineInOrderEntity> served) {
        for (DineInOrderEntity order : served) {
            order.setBillOrderId(bill.getId());
            order.setPaymentStatus(PaymentStateService.AWAITING);
            dineInOrderRepository.save(order);

            for (DineInOrderItemEntity item : dineInOrderItemRepository.findByOrderId(order.getId())) {
                OrderItemEntity copy = new OrderItemEntity();
                copy.setOrderId(bill.getId());
                copy.setProductId(item.getProductId());
                copy.setProductName(item.getProductName());
                copy.setPrice(item.getPrice());
                /*
                 * tb_order_items ทั้งระบบเก็บ quantity เป็น "ชิ้น" (เค้ก 1 ปอนด์ = 8)
                 * ออเดอร์โต๊ะเก็บเป็น "ออเดอร์" ถ้าคัดลอกตรงๆ หน้าเว็บจะหาร 8 ได้ 0
                 */
                copy.setQuantity(qtyHelper.toRawQty(item.getQuantity(), item.getSelectedOption()));
                copy.setSelectedOption(item.getSelectedOption());
                copy.setImage(item.getImage());
                copy.setCancelledQty(0);
                orderItemRepository.save(copy);
            }
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
