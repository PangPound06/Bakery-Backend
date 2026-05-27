package com.app.my_project.service;

import com.app.my_project.common.OrderCodeGenerator;
import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * OrderService — business logic ของ order ทั้งหมด ยกเว้น:
 *  - Item-level operations → OrderItemService
 *  - Stats/top-products → OrderStatsService
 *
 * Refactor highlights:
 *  - ลบ raw JDBC ออกหมด → ใช้ Repository + StockService
 *  - @Transactional ที่ระดับ service method (ครอบ rollback ทั้ง multi-step)
 *  - ดึง email และ admin check ไปทำใน controller layer
 *  - ใช้ OrderCodeGenerator helper สำหรับ ordCode
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final DineInOrderRepository dineInOrderRepository;
    private final StockService stockService;
    private final OrderCodeGenerator codeGenerator;

    public OrderService(OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        DineInOrderRepository dineInOrderRepository,
                        StockService stockService,
                        OrderCodeGenerator codeGenerator) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.dineInOrderRepository = dineInOrderRepository;
        this.stockService = stockService;
        this.codeGenerator = codeGenerator;
    }

    // ═════════════════════════════════════════════════════════════════
    // CREATE
    // ═════════════════════════════════════════════════════════════════

    /**
     * สร้าง order ใหม่ + save items + ลด stock (all-or-nothing transaction)
     * @return saved order entity (มี ordCode แล้ว)
     */
    @Transactional
    public OrderEntity createOrder(String email, CreateOrderRequest req) {
        // 1. save order header
        OrderEntity order = new OrderEntity();
        order.setEmail(email);
        order.setSubtotal(req.subtotal());
        order.setShipping(req.shipping());
        order.setTotal(req.total());
        order.setPaymentMethod(req.paymentMethod());
        order.setPaymentStatus(req.paymentStatus());
        order.setPaymentId(req.paymentId());
        order.setOrderType(req.orderType() != null ? req.orderType() : "online");
        order.setOrderStatus(req.orderStatus() != null ? req.orderStatus() : "pending");
        order.setSlipImage(req.slipImage());
        order.setCardName(req.cardName());
        order.setCardLast4(req.cardLast4());
        order.setReceiverName(req.receiverName());
        order.setReceiverPhone(req.receiverPhone());
        order.setReceiverAddress(req.receiverAddress());
        order.setNote(req.note());
        order.setCreatedAt(LocalDateTime.now());

        OrderEntity saved = orderRepository.save(order);
        saved.setOrdCode(codeGenerator.generate(saved.getId()));
        orderRepository.save(saved);

        // 2. save items + decrease stock
        if (req.items() != null) {
            for (OrderItemRequest item : req.items()) {
                OrderItemEntity entity = new OrderItemEntity();
                entity.setOrderId(saved.getId());
                entity.setProductId(item.productId());
                entity.setProductName(item.productName());
                entity.setPrice(item.price());
                entity.setQuantity(item.quantity()); // raw qty
                entity.setSelectedOption(item.selectedOption());
                entity.setImage(item.image());
                orderItemRepository.save(entity);

                stockService.decreaseStock(item.productId(), item.quantity());
            }
        }

        return saved;
    }

    // ═════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════

    public List<OrderEntity> getByEmail(String email) {
        return orderRepository.findByEmailOrderByCreatedAtDesc(email);
    }

    public Optional<OrderEntity> getById(Long id) {
        return orderRepository.findById(id);
    }

    public List<OrderEntity> getAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * ค้นจาก ordCode — ถ้าหาไม่เจอ ลอง reverse extract id จาก code แล้ว query ตรง
     * (กัน edge case ที่ order ไม่มี ordCode ใน DB — เช่น migrate ข้อมูลเก่า)
     */
    @Transactional
    public Optional<OrderEntity> searchByCode(String orderCode) {
        if (orderCode == null) return Optional.empty();
        String code = orderCode.toUpperCase().trim();

        Optional<OrderEntity> found = orderRepository.findByOrdCode(code);
        if (found.isPresent()) return found;

        Long extractedId = codeGenerator.extractOrderId(code);
        if (extractedId == null) return Optional.empty();

        return orderRepository.findById(extractedId).map(o -> {
            // ถ้า ordCode ใน DB หาย → set ให้ตรงกับที่ search
            if (o.getOrdCode() == null || o.getOrdCode().isEmpty()) {
                o.setOrdCode(code);
                return orderRepository.save(o);
            }
            return o;
        });
    }

    // ═════════════════════════════════════════════════════════════════
    // UPDATE
    // ═════════════════════════════════════════════════════════════════

    /**
     * Update orderStatus / paymentStatus
     * ถ้า paymentStatus=paid และ orderType=dine-in → sync paymentStatus dine-in orders
     *
     * @return true ถ้าสำเร็จ, false ถ้าไม่พบ
     */
    @Transactional
    public boolean updateStatus(Long orderId, String orderStatus, String paymentStatus) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) return false;

        OrderEntity order = opt.get();
        if (orderStatus != null) order.setOrderStatus(orderStatus);
        if (paymentStatus != null) order.setPaymentStatus(paymentStatus);
        orderRepository.save(order);

        // Side-effect: dine-in + paid → sync dine-in payment status
        if ("dine-in".equals(order.getOrderType()) && "paid".equals(paymentStatus)) {
            syncDineInPayment(order.getEmail());
        }
        return true;
    }

    private void syncDineInPayment(String email) {
        List<DineInOrderEntity> dineIns = dineInOrderRepository.findByEmailOrderByCreatedAtDesc(email);
        for (DineInOrderEntity d : dineIns) {
            if (!"cancelled".equals(d.getOrderStatus())) {
                d.setPaymentStatus("paid");
                dineInOrderRepository.save(d);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // CANCEL — restore stock + set status to cancelled
    // ═════════════════════════════════════════════════════════════════

    public enum CancelResult { SUCCESS, NOT_FOUND, ALREADY_DELIVERED }

    @Transactional
    public CancelResult cancelOrder(Long orderId) {
        Optional<OrderEntity> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) return CancelResult.NOT_FOUND;

        OrderEntity order = opt.get();
        if ("delivered".equals(order.getOrderStatus())) {
            return CancelResult.ALREADY_DELIVERED;
        }

        // restore stock for each item (batch แทน loop)
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(orderId);
        if (!items.isEmpty()) {
            List<StockService.StockChange> changes = items.stream()
                    .map(i -> new StockService.StockChange(i.getProductId(), i.getQuantity()))
                    .toList();
            stockService.batchIncreaseStock(changes);
        }

        order.setOrderStatus("cancelled");
        orderRepository.save(order);
        return CancelResult.SUCCESS;
    }

    // ═════════════════════════════════════════════════════════════════
    // BACKFILL — for legacy orders without ordCode
    // ═════════════════════════════════════════════════════════════════

    @Transactional
    public int backfillOrdCodes() {
        List<OrderEntity> orders = orderRepository.findAll();
        int count = 0;
        for (OrderEntity order : orders) {
            if (order.getOrdCode() == null || order.getOrdCode().isEmpty()) {
                order.setOrdCode(codeGenerator.generate(order.getId()));
                orderRepository.save(order);
                count++;
            }
        }
        return count;
    }

    // ═════════════════════════════════════════════════════════════════
    // DTOs (records)
    // ═════════════════════════════════════════════════════════════════

    public record CreateOrderRequest(
            Double subtotal,
            Double shipping,
            Double total,
            String paymentMethod,
            String paymentStatus,
            String paymentId,
            String orderType,
            String orderStatus,
            String slipImage,
            String cardName,
            String cardLast4,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String note,
            List<OrderItemRequest> items
    ) {}

    public record OrderItemRequest(
            Long productId,
            String productName,
            Double price,
            int quantity,           // raw quantity (ชิ้น)
            String selectedOption,
            String image
    ) {}
}