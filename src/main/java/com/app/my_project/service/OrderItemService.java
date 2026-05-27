package com.app.my_project.service;

import com.app.my_project.common.ProductQuantityHelper;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * OrderItemService — operations on order items (add/update/delete)
 *
 * แยกออกจาก OrderService เพราะ:
 *  - logic ซับซ้อน (stock adjustment + recalculate totals + cancel-if-empty)
 *  - ทดสอบแยกได้ง่ายกว่า
 *  - ทุก operation ต้อง recalculate subtotal/total ของ parent order
 */
@Service
public class OrderItemService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockService stockService;
    private final ProductQuantityHelper qtyHelper;

    public OrderItemService(OrderRepository orderRepository,
                            OrderItemRepository orderItemRepository,
                            StockService stockService,
                            ProductQuantityHelper qtyHelper) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockService = stockService;
        this.qtyHelper = qtyHelper;
    }

    // ═════════════════════════════════════════════════════════════════
    public enum Result {
        SUCCESS,
        ORDER_NOT_FOUND,
        ITEM_NOT_FOUND,
        ITEM_NOT_IN_ORDER,
        ORDER_LOCKED,        // delivered/cancelled — can't edit
        ORDER_CANCELLED      // เมื่อลบ item สุดท้าย order ถูก cancel auto
    }

    // ═════════════════════════════════════════════════════════════════
    // ADD ITEM
    // ═════════════════════════════════════════════════════════════════

    @Transactional
    public AddItemResult addItem(Long orderId, AddItemRequest req) {
        Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            return new AddItemResult(Result.ORDER_NOT_FOUND, null, null);
        }

        OrderEntity order = orderOpt.get();
        if (isLocked(order)) {
            return new AddItemResult(Result.ORDER_LOCKED, null, null);
        }

        // แปลง display qty → raw qty
        int rawQty = qtyHelper.toRawQty(req.displayQty(), req.selectedOption());

        // ลด stock
        stockService.decreaseStock(req.productId(), rawQty);

        // บันทึก item
        OrderItemEntity newItem = new OrderItemEntity();
        newItem.setOrderId(orderId);
        newItem.setProductId(req.productId());
        newItem.setProductName(req.productName());
        newItem.setPrice(req.price());
        newItem.setQuantity(rawQty);
        newItem.setSelectedOption(req.selectedOption());
        newItem.setImage(req.image());
        OrderItemEntity saved = orderItemRepository.save(newItem);

        // คำนวณยอดใหม่
        Totals totals = recalculateOrderTotals(orderId, order.getShipping());
        order.setSubtotal(totals.subtotal());
        order.setTotal(totals.total());
        orderRepository.save(order);

        return new AddItemResult(Result.SUCCESS, saved, totals);
    }

    // ═════════════════════════════════════════════════════════════════
    // UPDATE QUANTITY
    // ═════════════════════════════════════════════════════════════════

    @Transactional
    public UpdateQtyResult updateQuantity(Long orderId, Long itemId, int newDisplayQty) {
        Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) return new UpdateQtyResult(Result.ORDER_NOT_FOUND, null);

        OrderEntity order = orderOpt.get();
        if (isLocked(order)) return new UpdateQtyResult(Result.ORDER_LOCKED, null);

        Optional<OrderItemEntity> itemOpt = orderItemRepository.findById(itemId);
        if (itemOpt.isEmpty()) return new UpdateQtyResult(Result.ITEM_NOT_FOUND, null);

        OrderItemEntity item = itemOpt.get();
        if (!item.getOrderId().equals(orderId)) {
            return new UpdateQtyResult(Result.ITEM_NOT_IN_ORDER, null);
        }

        // คำนวณ diff (positive = เพิ่ม / negative = ลด)
        int oldQty = item.getQuantity();
        int newRawQty = qtyHelper.toRawQty(newDisplayQty, item.getSelectedOption());
        int diff = newRawQty - oldQty;

        // ปรับ stock
        if (diff > 0) {
            stockService.decreaseStock(item.getProductId(), diff);
        } else if (diff < 0) {
            stockService.increaseStock(item.getProductId(), Math.abs(diff));
        }

        item.setQuantity(newRawQty);
        orderItemRepository.save(item);

        // คำนวณยอดใหม่
        Totals totals = recalculateOrderTotals(orderId, order.getShipping());
        order.setSubtotal(totals.subtotal());
        order.setTotal(totals.total());
        orderRepository.save(order);

        return new UpdateQtyResult(Result.SUCCESS, totals);
    }

    // ═════════════════════════════════════════════════════════════════
    // REMOVE ITEM
    // ═════════════════════════════════════════════════════════════════

    @Transactional
    public RemoveItemResult removeItem(Long orderId, Long itemId) {
        Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) return new RemoveItemResult(Result.ORDER_NOT_FOUND, null);

        OrderEntity order = orderOpt.get();
        if (isLocked(order)) return new RemoveItemResult(Result.ORDER_LOCKED, null);

        Optional<OrderItemEntity> itemOpt = orderItemRepository.findById(itemId);
        if (itemOpt.isEmpty()) return new RemoveItemResult(Result.ITEM_NOT_FOUND, null);

        OrderItemEntity item = itemOpt.get();
        if (!item.getOrderId().equals(orderId)) {
            return new RemoveItemResult(Result.ITEM_NOT_IN_ORDER, null);
        }

        // คืน stock
        stockService.increaseStock(item.getProductId(), item.getQuantity());

        orderItemRepository.deleteById(itemId);

        // ถ้าไม่มี item เหลือ → cancel order อัตโนมัติ
        List<OrderItemEntity> remaining = orderItemRepository.findByOrderId(orderId);
        if (remaining.isEmpty()) {
            order.setOrderStatus("cancelled");
            orderRepository.save(order);
            return new RemoveItemResult(Result.ORDER_CANCELLED, null);
        }

        // recalc totals
        Totals totals = recalculateOrderTotals(orderId, order.getShipping());
        order.setSubtotal(totals.subtotal());
        order.setTotal(totals.total());
        orderRepository.save(order);

        return new RemoveItemResult(Result.SUCCESS, totals);
    }

    // ═════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════

    boolean isLocked(OrderEntity order) {
        return "delivered".equals(order.getOrderStatus())
                || "cancelled".equals(order.getOrderStatus());
    }

    /** คำนวณ subtotal/total จาก items ปัจจุบันของ order */
    Totals recalculateOrderTotals(Long orderId, Double shipping) {
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(orderId);
        double subtotal = items.stream()
                .mapToDouble(i -> i.getPrice() * qtyHelper.toDisplayQty(i.getQuantity(), i.getSelectedOption()))
                .sum();
        double effectiveShipping = shipping != null ? shipping : 0.0;
        return new Totals(subtotal, subtotal + effectiveShipping);
    }

    // ═════════════════════════════════════════════════════════════════
    // DTOs
    // ═════════════════════════════════════════════════════════════════

    public record AddItemRequest(
            Long productId,
            String productName,
            Double price,
            int displayQty,
            String selectedOption,
            String image
    ) {}

    public record Totals(double subtotal, double total) {}

    public record AddItemResult(Result result, OrderItemEntity savedItem, Totals totals) {}
    public record UpdateQtyResult(Result result, Totals totals) {}
    public record RemoveItemResult(Result result, Totals totals) {}
}