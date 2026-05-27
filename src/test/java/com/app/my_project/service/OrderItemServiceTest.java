package com.app.my_project.service;

import com.app.my_project.common.ProductQuantityHelper;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderItemServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private StockService stockService;

    // ใช้ real instance ของ helper เพราะเป็น pure logic
    private final ProductQuantityHelper qtyHelper = new ProductQuantityHelper();

    private OrderItemService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new OrderItemService(orderRepository, orderItemRepository, stockService, qtyHelper);
    }

    private OrderEntity makeOrder(Long id, String status) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setOrderStatus(status);
        o.setShipping(30.0);
        return o;
    }

    private OrderItemEntity makeItem(Long itemId, Long orderId, Long productId,
                                      double price, int qty, String option) {
        OrderItemEntity i = new OrderItemEntity();
        i.setId(itemId);
        i.setOrderId(orderId);
        i.setProductId(productId);
        i.setPrice(price);
        i.setQuantity(qty);
        i.setSelectedOption(option);
        return i;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("addItem()")
    class AddItemTests {

        @Test
        @DisplayName("Happy path: ลด stock + save item + update totals")
        void addItem_success() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderItemRepository.save(any())).thenAnswer(inv -> {
                OrderItemEntity e = inv.getArgument(0);
                e.setId(100L);
                return e;
            });
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(
                    makeItem(100L, 1L, 10L, 100.0, 8, "1 ปอนด์")
            ));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrderItemService.AddItemResult result = service.addItem(1L,
                    new OrderItemService.AddItemRequest(
                            10L, "Cake", 100.0, 1, "1 ปอนด์", "img.jpg"
                    ));

            assertThat(result.result()).isEqualTo(OrderItemService.Result.SUCCESS);

            // ✅ ลด stock raw qty (1 ออเดอร์ * 8 ชิ้น = 8)
            verify(stockService).decreaseStock(10L, 8);

            // verify totals (price * displayQty = 100 * 1 = 100, total = 100 + 30 shipping)
            assertThat(result.totals().subtotal()).isEqualTo(100.0);
            assertThat(result.totals().total()).isEqualTo(130.0);
        }

        @Test
        @DisplayName("Order ไม่พบ → ORDER_NOT_FOUND")
        void addItem_orderNotFound() {
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            OrderItemService.AddItemResult result = service.addItem(999L,
                    new OrderItemService.AddItemRequest(10L, "Cake", 100.0, 1, null, null));

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ORDER_NOT_FOUND);
            verifyNoInteractions(stockService);
        }

        @Test
        @DisplayName("✅ Order delivered → ORDER_LOCKED (ห้ามแก้ไข)")
        void addItem_orderDelivered_locked() {
            OrderEntity order = makeOrder(1L, "delivered");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemService.AddItemResult result = service.addItem(1L,
                    new OrderItemService.AddItemRequest(10L, "Cake", 100.0, 1, null, null));

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ORDER_LOCKED);
            verifyNoInteractions(stockService);
            verify(orderItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("✅ Order cancelled → ORDER_LOCKED")
        void addItem_orderCancelled_locked() {
            OrderEntity order = makeOrder(1L, "cancelled");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemService.AddItemResult result = service.addItem(1L,
                    new OrderItemService.AddItemRequest(10L, "Cake", 100.0, 1, null, null));

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ORDER_LOCKED);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateQuantity()")
    class UpdateQtyTests {

        @Test
        @DisplayName("✅ เพิ่ม qty → ลด stock เพิ่ม (diff > 0 → decreaseStock)")
        void increaseQty_decreasesStock() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemEntity item = makeItem(100L, 1L, 10L, 100.0, 8, "1 ปอนด์"); // 1 ออเดอร์ = 8 ชิ้น
            when(orderItemRepository.findById(100L)).thenReturn(Optional.of(item));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(item));
            when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrderItemService.UpdateQtyResult result = service.updateQuantity(1L, 100L, 3); // 1 → 3 ออเดอร์

            assertThat(result.result()).isEqualTo(OrderItemService.Result.SUCCESS);
            // diff = (3 * 8) - 8 = 16 → decreaseStock 16
            verify(stockService).decreaseStock(10L, 16);
            verify(stockService, never()).increaseStock(any(), anyInt());
        }

        @Test
        @DisplayName("✅ ลด qty → คืน stock (diff < 0 → increaseStock)")
        void decreaseQty_increasesStock() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemEntity item = makeItem(100L, 1L, 10L, 100.0, 24, "1 ปอนด์"); // 3 ออเดอร์ = 24 ชิ้น
            when(orderItemRepository.findById(100L)).thenReturn(Optional.of(item));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(item));
            when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.updateQuantity(1L, 100L, 1); // 3 → 1 ออเดอร์

            // diff = (1 * 8) - 24 = -16 → increaseStock 16
            verify(stockService).increaseStock(10L, 16);
            verify(stockService, never()).decreaseStock(any(), anyInt());
        }

        @Test
        @DisplayName("Order ไม่พบ → ORDER_NOT_FOUND")
        void orderNotFound() {
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            OrderItemService.UpdateQtyResult result = service.updateQuantity(999L, 100L, 1);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("Item ไม่พบ → ITEM_NOT_FOUND")
        void itemNotFound() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderItemRepository.findById(999L)).thenReturn(Optional.empty());

            OrderItemService.UpdateQtyResult result = service.updateQuantity(1L, 999L, 1);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("✅ Item ไม่ได้อยู่ใน order ที่ระบุ → ITEM_NOT_IN_ORDER")
        void itemNotInOrder() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            // Item นี้อยู่ใน order 2 ไม่ใช่ 1
            OrderItemEntity item = makeItem(100L, 2L, 10L, 100.0, 8, null);
            when(orderItemRepository.findById(100L)).thenReturn(Optional.of(item));

            OrderItemService.UpdateQtyResult result = service.updateQuantity(1L, 100L, 1);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ITEM_NOT_IN_ORDER);
            verifyNoInteractions(stockService);
        }

        @Test
        @DisplayName("Order locked → ORDER_LOCKED")
        void orderLocked() {
            OrderEntity order = makeOrder(1L, "delivered");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemService.UpdateQtyResult result = service.updateQuantity(1L, 100L, 1);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ORDER_LOCKED);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("removeItem()")
    class RemoveTests {

        @Test
        @DisplayName("Happy path: คืน stock + ลบ item + ยังเหลือ item อื่น → recalc")
        void remove_withRemaining_recalcs() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemEntity removedItem = makeItem(100L, 1L, 10L, 100.0, 8, "1 ปอนด์");
            when(orderItemRepository.findById(100L)).thenReturn(Optional.of(removedItem));

            // หลังลบ ยังเหลือ item อื่น
            OrderItemEntity remaining = makeItem(200L, 1L, 20L, 50.0, 1, null);
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(remaining));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrderItemService.RemoveItemResult result = service.removeItem(1L, 100L);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.SUCCESS);
            verify(stockService).increaseStock(10L, 8);
            verify(orderItemRepository).deleteById(100L);
            // ✅ subtotal = 50 * 1 = 50, total = 50 + 30 shipping = 80
            assertThat(result.totals().subtotal()).isEqualTo(50.0);
            assertThat(result.totals().total()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("✅ ลบ item สุดท้าย → order auto cancel")
        void remove_lastItem_autoCancelsOrder() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemEntity item = makeItem(100L, 1L, 10L, 100.0, 8, "1 ปอนด์");
            when(orderItemRepository.findById(100L)).thenReturn(Optional.of(item));
            // หลังลบ ไม่มี items เหลือ
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrderItemService.RemoveItemResult result = service.removeItem(1L, 100L);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ORDER_CANCELLED);
            // verify order ถูก set status เป็น cancelled
            ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
            verify(orderRepository).save(captor.capture());
            assertThat(captor.getValue().getOrderStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("Item ไม่อยู่ใน order → ITEM_NOT_IN_ORDER")
        void itemNotInOrder_returnsError() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemEntity item = makeItem(100L, 2L, 10L, 100.0, 8, null);
            when(orderItemRepository.findById(100L)).thenReturn(Optional.of(item));

            OrderItemService.RemoveItemResult result = service.removeItem(1L, 100L);

            assertThat(result.result()).isEqualTo(OrderItemService.Result.ITEM_NOT_IN_ORDER);
            verifyNoInteractions(stockService);
        }

        @Test
        @DisplayName("Order ไม่พบ → ORDER_NOT_FOUND")
        void orderNotFound() {
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            assertThat(service.removeItem(999L, 100L).result())
                    .isEqualTo(OrderItemService.Result.ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("Item ไม่พบ → ITEM_NOT_FOUND")
        void itemNotFound() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderItemRepository.findById(999L)).thenReturn(Optional.empty());

            assertThat(service.removeItem(1L, 999L).result())
                    .isEqualTo(OrderItemService.Result.ITEM_NOT_FOUND);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("recalculateOrderTotals() — helper")
    class RecalculateTests {

        @Test
        @DisplayName("คำนวณจาก display qty (ไม่ใช่ raw qty)")
        void usesDisplayQty() {
            // item: 1 ปอนด์, raw=16 (= 2 ออเดอร์), price=100 → subtotal = 200
            OrderItemEntity item = makeItem(100L, 1L, 10L, 100.0, 16, "1 ปอนด์");
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(item));

            OrderItemService.Totals totals = service.recalculateOrderTotals(1L, 30.0);

            assertThat(totals.subtotal()).isEqualTo(200.0); // 100 * 2 (display qty)
            assertThat(totals.total()).isEqualTo(230.0);
        }

        @Test
        @DisplayName("shipping = null → ใช้ 0")
        void nullShipping_uses0() {
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(
                    makeItem(100L, 1L, 10L, 50.0, 1, null)
            ));

            OrderItemService.Totals totals = service.recalculateOrderTotals(1L, null);

            assertThat(totals.subtotal()).isEqualTo(50.0);
            assertThat(totals.total()).isEqualTo(50.0);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("isLocked() — helper")
    class IsLockedTests {

        @Test
        @DisplayName("status=delivered → locked")
        void delivered_isLocked() {
            assertThat(service.isLocked(makeOrder(1L, "delivered"))).isTrue();
        }

        @Test
        @DisplayName("status=cancelled → locked")
        void cancelled_isLocked() {
            assertThat(service.isLocked(makeOrder(1L, "cancelled"))).isTrue();
        }

        @Test
        @DisplayName("status=pending → not locked")
        void pending_notLocked() {
            assertThat(service.isLocked(makeOrder(1L, "pending"))).isFalse();
        }

        @Test
        @DisplayName("status=preparing → not locked")
        void preparing_notLocked() {
            assertThat(service.isLocked(makeOrder(1L, "preparing"))).isFalse();
        }
    }
}