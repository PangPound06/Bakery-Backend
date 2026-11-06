package com.app.my_project.service;

import com.app.my_project.common.PaymentMethods;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * รอบจ่ายเงินที่ไม่ได้เงินต้องถูกลบทิ้ง ไม่เหลือแถว expired/cancelled ในฐานข้อมูล
 */
@ExtendWith(MockitoExtension.class)
class PaymentStateServiceDiscardTest {

    private static final String EMAIL = "cus@test.com";

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private StockService stockService;
    @Mock private CartService cartService;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PaymentStateService service;

    private static OrderEntity order(long id, String type, String paymentStatus) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setEmail(EMAIL);
        o.setOrderType(type);
        o.setPaymentMethod(PaymentMethods.QR_PROMPTPAY);
        o.setPaymentStatus(paymentStatus);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }

    private static OrderItemEntity item(long productId, int qty) {
        OrderItemEntity i = new OrderItemEntity();
        i.setProductId(productId);
        i.setQuantity(qty);
        return i;
    }

    @Nested
    @DisplayName("discardUnpaid")
    class Discard {

        @Test
        @DisplayName("ออนไลน์รอจ่าย → คืน stock แล้วลบ order + รายการ")
        void onlineAwaiting_restoresStockAndDeletes() {
            OrderEntity o = order(1L, "online", PaymentStateService.AWAITING);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(item(10L, 2)));

            assertThat(service.discardUnpaid(1L, "ลูกค้ายกเลิกเอง")).isTrue();

            verify(stockService).batchIncreaseStock(argThat(list -> list.size() == 1));
            verify(orderItemRepository).deleteByOrderId(1L);
            verify(orderRepository).delete(o);
        }

        @Test
        @DisplayName("บิลหน้าร้าน → ไม่คืน stock (กินไปแล้ว) ปลดออเดอร์โต๊ะแล้วลบบิล")
        void dineInBill_unlinksTableOrdersWithoutStock() {
            OrderEntity o = order(2L, "dine-in", PaymentStateService.AWAITING);
            when(orderRepository.findById(2L)).thenReturn(Optional.of(o));

            assertThat(service.discardUnpaid(2L, "ลูกค้ายกเลิก")).isTrue();

            verifyNoInteractions(stockService);
            verify(jdbcTemplate).update(contains("bill_order_id = NULL"), eq(2L));
            verify(orderRepository).delete(o);
        }

        @Test
        @DisplayName("จ่ายแล้ว / แจ้งโอนแล้ว / สลิปถูกปฏิเสธ → ไม่ลบ")
        void protectedStatuses_kept() {
            OrderEntity paid = order(3L, "online", PaymentStateService.PAID);
            OrderEntity verifying = order(4L, "online", PaymentStateService.VERIFYING);
            OrderEntity rejected = order(5L, "online", PaymentStateService.FAILED);
            rejected.setSlipImage("https://res.cloudinary.com/demo/slip.png");
            when(orderRepository.findById(3L)).thenReturn(Optional.of(paid));
            when(orderRepository.findById(4L)).thenReturn(Optional.of(verifying));
            when(orderRepository.findById(5L)).thenReturn(Optional.of(rejected));

            assertThat(service.discardUnpaid(3L, "x")).isFalse();
            assertThat(service.discardUnpaid(4L, "x")).isFalse();
            assertThat(service.discardUnpaid(5L, "x")).isFalse();
            verify(orderRepository, never()).delete(any(OrderEntity.class));
            verifyNoInteractions(stockService);
        }

        @Test
        @DisplayName("แถว expired สมัยก่อน → ลบอย่างเดียว ไม่คืน stock ซ้ำ")
        void legacyExpired_deletedWithoutRestock() {
            OrderEntity o = order(6L, "online", PaymentStateService.EXPIRED);
            when(orderRepository.findById(6L)).thenReturn(Optional.of(o));

            assertThat(service.discardUnpaid(6L, "ล้างข้อมูลเก่า")).isTrue();
            verifyNoInteractions(stockService);
            verify(orderRepository).delete(o);
        }

        @Test
        @DisplayName("ไม่มี order (ถูกลบไปแล้ว) → false ไม่ error")
        void missing_returnsFalse() {
            when(orderRepository.findById(9L)).thenReturn(Optional.empty());
            assertThat(service.discardUnpaid(9L, "x")).isFalse();
        }
    }

    @Nested
    @DisplayName("prepareForNewIntent")
    class Reuse {

        private final List<PaymentStateService.CartLine> cart = List.of(
                new PaymentStateService.CartLine(10L, "ครัวซองต์", null, 1, 55.0, "img"));

        @Test
        @DisplayName("ไม่มีแถวรอจ่าย → null (ให้สร้างใหม่)")
        void nothingOpen_returnsNull() {
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.AWAITING)).thenReturn(List.of());

            assertThat(service.prepareForNewIntent(EMAIL, Set.of(), cart, 55, 50, 105)).isNull();
        }

        @Test
        @DisplayName("ใช้แถวล่าสุดต่อ เลข order เดิม — แถวเก่าเกินเวลาถูกลบทิ้ง")
        void reusesLatest_discardsStale() {
            OrderEntity fresh = order(20L, "online", PaymentStateService.AWAITING);
            OrderEntity stale = order(11L, "online", PaymentStateService.AWAITING);
            stale.setCreatedAt(LocalDateTime.now().minus(PaymentStateService.UNPAID_TTL).minusMinutes(1));
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.AWAITING))
                    .thenReturn(List.of(stale, fresh));

            OrderEntity kept = service.prepareForNewIntent(EMAIL, Set.of(), cart, 55, 50, 105);

            assertThat(kept).isSameAs(fresh);
            assertThat(kept.getTotal()).isEqualTo(105);
            verify(orderRepository).delete(stale);
            verify(orderRepository, never()).delete(fresh);
            verify(stockService).decreaseStock(10L, 1);
        }

        @Test
        @DisplayName("แถวที่ถูกล็อก (กำลังตัดเงิน) และบิลหน้าร้าน → ไม่แตะ")
        void lockedAndDineIn_untouched() {
            OrderEntity locked = order(30L, "online", PaymentStateService.AWAITING);
            OrderEntity bill = order(31L, "dine-in", PaymentStateService.AWAITING);
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.AWAITING))
                    .thenReturn(List.of(locked, bill));

            assertThat(service.prepareForNewIntent(EMAIL, Set.of(30L), cart, 55, 50, 105)).isNull();
            verify(orderRepository, never()).delete(any(OrderEntity.class));
        }
    }
}
