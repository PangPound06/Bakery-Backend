package com.app.my_project.service;

import com.app.my_project.common.OrderCodeGenerator;
import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private DineInOrderRepository dineInOrderRepository;
    @Mock private StockService stockService;
    @Mock private OrderCodeGenerator codeGenerator;

    @InjectMocks
    private OrderService orderService;

    private OrderService.CreateOrderRequest sampleCreateRequest() {
        return new OrderService.CreateOrderRequest(
                100.0, 30.0, 130.0,
                "card", "paid", "pi_123",
                "online", "pending",
                null, "John Doe", "1234",
                "John", "0812345678", "Bangkok",
                "note",
                List.of(new OrderService.OrderItemRequest(
                        10L, "Cake", 100.0, 8, "1 ปอนด์", "img.jpg"
                ))
        );
    }

    private OrderEntity makeOrder(Long id, String status) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setEmail("test@example.com");
        o.setOrderStatus(status);
        return o;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createOrder()")
    class CreateTests {

        @Test
        @DisplayName("Happy path: สร้าง order + items + ลด stock")
        void createOrder_savesEverything() {
            when(orderRepository.save(any())).thenAnswer(inv -> {
                OrderEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(1L);
                return e;
            });
            when(codeGenerator.generate(1L)).thenReturn("ORD1047291");

            OrderEntity result = orderService.createOrder("user@test.com", sampleCreateRequest());

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getOrdCode()).isEqualTo("ORD1047291");

            // verify save order (2 ครั้ง: ครั้งแรกได้ id, ครั้ง 2 set ordCode)
            verify(orderRepository, times(2)).save(any());
            // verify save item
            verify(orderItemRepository).save(any(OrderItemEntity.class));
            // verify decrease stock
            verify(stockService).decreaseStock(10L, 8);
        }

        @Test
        @DisplayName("Default values: orderType=online, orderStatus=pending")
        void createOrder_appliesDefaults() {
            OrderService.CreateOrderRequest req = new OrderService.CreateOrderRequest(
                    50.0, 0.0, 50.0, "card", "paid", null,
                    null, null,  // ← orderType/orderStatus = null
                    null, null, null, null, null, null, null, null
            );

            when(orderRepository.save(any())).thenAnswer(inv -> {
                OrderEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(1L);
                return e;
            });
            when(codeGenerator.generate(any())).thenReturn("ORD123");

            orderService.createOrder("user@test.com", req);

            ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
            verify(orderRepository, atLeastOnce()).save(captor.capture());
            // ตัวแรกที่ save: ก่อน setOrdCode
            OrderEntity firstSave = captor.getAllValues().get(0);
            assertThat(firstSave.getOrderType()).isEqualTo("online");
            assertThat(firstSave.getOrderStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("items = null → ไม่เรียก orderItemRepository.save")
        void createOrder_nullItems_noItemsSaved() {
            OrderService.CreateOrderRequest req = new OrderService.CreateOrderRequest(
                    50.0, 0.0, 50.0, "card", "paid", null,
                    "online", "pending", null, null, null, null, null, null, null,
                    null  // ← items = null
            );

            when(orderRepository.save(any())).thenAnswer(inv -> {
                OrderEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(1L);
                return e;
            });
            when(codeGenerator.generate(any())).thenReturn("ORD123");

            orderService.createOrder("u@e.com", req);

            verify(orderItemRepository, never()).save(any());
            verify(stockService, never()).decreaseStock(any(), anyInt());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("READ methods")
    class ReadTests {

        @Test
        @DisplayName("getByEmail → forward to repository")
        void getByEmail_forwardsToRepo() {
            when(orderRepository.findByEmailOrderByCreatedAtDesc("a@b.com"))
                    .thenReturn(List.of(makeOrder(1L, "pending")));

            List<OrderEntity> result = orderService.getByEmail("a@b.com");

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("getById → forward to repository")
        void getById_forwardsToRepo() {
            when(orderRepository.findById(1L)).thenReturn(Optional.of(makeOrder(1L, "pending")));

            assertThat(orderService.getById(1L)).isPresent();
        }

        @Test
        @DisplayName("getAll → forward to repository")
        void getAll_forwardsToRepo() {
            when(orderRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(makeOrder(1L, "pending")));

            assertThat(orderService.getAll()).hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("searchByCode()")
    class SearchTests {

        @Test
        @DisplayName("เจอ ordCode ตรง → return immediately")
        void search_directHit_returnsOrder() {
            OrderEntity order = makeOrder(1L, "pending");
            order.setOrdCode("ORD1047291");
            when(orderRepository.findByOrdCode("ORD1047291")).thenReturn(Optional.of(order));

            Optional<OrderEntity> result = orderService.searchByCode("ORD1047291");

            assertThat(result).isPresent();
            // ไม่ได้เรียก extractOrderId
            verify(codeGenerator, never()).extractOrderId(any());
        }

        @Test
        @DisplayName("✅ ordCode ไม่ตรง → reverse extract id → query findById")
        void search_reverseExtract_succeeds() {
            when(orderRepository.findByOrdCode(anyString())).thenReturn(Optional.empty());
            when(codeGenerator.extractOrderId("ORD1047291")).thenReturn(1L);

            OrderEntity order = makeOrder(1L, "pending");
            order.setOrdCode("ORD1047291"); // มีอยู่แล้ว
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            Optional<OrderEntity> result = orderService.searchByCode("ORD1047291");

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("ordCode ใน DB หาย (null) → set ใหม่จาก code ที่ search")
        void search_missingOrdCodeInDB_setsIt() {
            when(orderRepository.findByOrdCode(anyString())).thenReturn(Optional.empty());
            when(codeGenerator.extractOrderId("ORD1047291")).thenReturn(1L);

            OrderEntity orderWithoutCode = makeOrder(1L, "pending");
            orderWithoutCode.setOrdCode(null); // missing
            when(orderRepository.findById(1L)).thenReturn(Optional.of(orderWithoutCode));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            orderService.searchByCode("ORD1047291");

            // verify ว่ามีการ save (set ordCode)
            verify(orderRepository).save(argThat(o -> "ORD1047291".equals(o.getOrdCode())));
        }

        @Test
        @DisplayName("Code format ผิด → return empty")
        void search_invalidFormat_returnsEmpty() {
            when(orderRepository.findByOrdCode(anyString())).thenReturn(Optional.empty());
            when(codeGenerator.extractOrderId(anyString())).thenReturn(null);

            assertThat(orderService.searchByCode("BADCODE")).isEmpty();
        }

        @Test
        @DisplayName("null code → return empty (ไม่ throw)")
        void search_nullCode_returnsEmpty() {
            assertThat(orderService.searchByCode(null)).isEmpty();
            verifyNoInteractions(orderRepository);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateStatus()")
    class UpdateStatusTests {

        @Test
        @DisplayName("Update ทั้ง order + payment status")
        void updateBoth_savesBoth() {
            OrderEntity order = makeOrder(1L, "pending");
            order.setPaymentStatus("unpaid");
            order.setOrderType("online");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = orderService.updateStatus(1L, "shipping", "paid");

            assertThat(result).isTrue();
            ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
            verify(orderRepository).save(captor.capture());
            assertThat(captor.getValue().getOrderStatus()).isEqualTo("shipping");
            assertThat(captor.getValue().getPaymentStatus()).isEqualTo("paid");
        }

        @Test
        @DisplayName("Order ไม่พบ → return false")
        void notFound_returnsFalse() {
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            assertThat(orderService.updateStatus(999L, "shipping", null)).isFalse();
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("✅ dine-in + paid → sync dine-in payment status")
        void dineInPaid_syncsDineInOrders() {
            OrderEntity order = makeOrder(1L, "completed");
            order.setOrderType("dine-in");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            DineInOrderEntity di1 = new DineInOrderEntity();
            di1.setOrderStatus("pending");
            DineInOrderEntity di2 = new DineInOrderEntity();
            di2.setOrderStatus("cancelled"); // จะถูกข้าม
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc("test@example.com"))
                    .thenReturn(List.of(di1, di2));

            orderService.updateStatus(1L, null, "paid");

            // verify di1 ถูก save (paid)
            verify(dineInOrderRepository).save(di1);
            // verify di2 ไม่ถูก save (cancelled = ข้าม)
            verify(dineInOrderRepository, never()).save(di2);
        }

        @Test
        @DisplayName("✅ online order + paid → ไม่ sync dine-in")
        void onlinePaid_doesNotSyncDineIn() {
            OrderEntity order = makeOrder(1L, "completed");
            order.setOrderType("online");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            orderService.updateStatus(1L, null, "paid");

            verifyNoInteractions(dineInOrderRepository);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("cancelOrder()")
    class CancelTests {

        @Test
        @DisplayName("Happy path: cancel + restore stock + status=cancelled")
        void cancel_success_restoresStock() {
            OrderEntity order = makeOrder(1L, "preparing");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderItemEntity item1 = new OrderItemEntity();
            item1.setProductId(10L);
            item1.setQuantity(2);
            OrderItemEntity item2 = new OrderItemEntity();
            item2.setProductId(20L);
            item2.setQuantity(3);
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(item1, item2));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrderService.CancelResult result = orderService.cancelOrder(1L);

            assertThat(result).isEqualTo(OrderService.CancelResult.SUCCESS);

            // ✅ ใช้ batchIncreaseStock — ไม่ loop increaseStock
            verify(stockService).batchIncreaseStock(argThat(list -> list.size() == 2));
            verify(stockService, never()).increaseStock(any(), anyInt());

            // verify ว่า status = cancelled
            verify(orderRepository).save(argThat(o -> "cancelled".equals(o.getOrderStatus())));
        }

        @Test
        @DisplayName("✅ ห้าม cancel order ที่ delivered แล้ว")
        void cancel_deliveredOrder_returnsAlreadyDelivered() {
            OrderEntity order = makeOrder(1L, "delivered");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            OrderService.CancelResult result = orderService.cancelOrder(1L);

            assertThat(result).isEqualTo(OrderService.CancelResult.ALREADY_DELIVERED);
            verify(stockService, never()).batchIncreaseStock(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Order ไม่พบ → return NOT_FOUND")
        void cancel_notFound_returnsNotFound() {
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            OrderService.CancelResult result = orderService.cancelOrder(999L);

            assertThat(result).isEqualTo(OrderService.CancelResult.NOT_FOUND);
        }

        @Test
        @DisplayName("Order ไม่มี items → ไม่เรียก batchIncreaseStock")
        void cancel_noItems_stillCancels() {
            OrderEntity order = makeOrder(1L, "pending");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrderService.CancelResult result = orderService.cancelOrder(1L);

            assertThat(result).isEqualTo(OrderService.CancelResult.SUCCESS);
            verify(stockService, never()).batchIncreaseStock(any());
            verify(orderRepository).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("backfillOrdCodes()")
    class BackfillTests {

        @Test
        @DisplayName("✅ เฉพาะ order ที่ ordCode = null/empty → generate")
        void backfill_onlyMissingCodes() {
            OrderEntity withCode = makeOrder(1L, "pending");
            withCode.setOrdCode("ORD1047291"); // มีแล้ว
            OrderEntity nullCode = makeOrder(2L, "pending");
            nullCode.setOrdCode(null); // null
            OrderEntity emptyCode = makeOrder(3L, "pending");
            emptyCode.setOrdCode(""); // empty

            when(orderRepository.findAll()).thenReturn(List.of(withCode, nullCode, emptyCode));
            when(codeGenerator.generate(2L)).thenReturn("ORD2094584-2");
            when(codeGenerator.generate(3L)).thenReturn("ORD3141873-3");
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int count = orderService.backfillOrdCodes();

            assertThat(count).isEqualTo(2); // เฉพาะ 2 และ 3
            verify(codeGenerator, never()).generate(1L); // 1 มี code อยู่แล้ว
            verify(orderRepository, times(2)).save(any());
        }
    }
}