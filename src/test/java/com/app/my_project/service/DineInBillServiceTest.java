package com.app.my_project.service;

import com.app.my_project.common.CartMode;
import com.app.my_project.common.CheckoutMethod;
import com.app.my_project.common.OrderCodeGenerator;
import com.app.my_project.common.PaymentMethods;
import com.app.my_project.common.ProductQuantityHelper;
import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DineInBillService — ราคาต้องมาจาก server และห้ามออกบิลซ้อนกับรอบที่ยังไม่จบ
 */
@ExtendWith(MockitoExtension.class)
class DineInBillServiceTest {

    private static final String EMAIL = "guest@test.com";

    @Mock private DineInOrderRepository dineInOrderRepository;
    @Mock private DineInOrderItemRepository dineInOrderItemRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private PricingService pricingService;
    @Mock private StockService stockService;
    @Mock private CartService cartService;
    @Mock private PaymentStateService paymentStateService;
    @Mock private PaymentReconciler paymentReconciler;

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    /** เวลาปัจจุบันที่ใช้ทดสอบ — 20:00 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 20, 0);

    private DineInBillService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.atZone(BANGKOK).toInstant(), BANGKOK);
        service = new DineInBillService(dineInOrderRepository, dineInOrderItemRepository, orderRepository,
                orderItemRepository, pricingService, stockService, cartService, paymentStateService,
                paymentReconciler, new OrderCodeGenerator(), new ProductQuantityHelper(), fixed);
    }

    private static DineInOrderEntity buffetOrder(long id, LocalDateTime createdAt) {
        DineInOrderEntity o = tableOrder(id, "5", "completed", 0);
        o.setDineType("buffet");
        o.setCreatedAt(createdAt);
        return o;
    }

    private static DineInOrderEntity tableOrder(long id, String table, String status, double total) {
        DineInOrderEntity o = new DineInOrderEntity();
        o.setId(id);
        o.setEmail(EMAIL);
        o.setTableNo(table);
        o.setOrderStatus(status);
        o.setTotal(total);
        o.setSubtotal(total);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }

    private void echoSaves() {
        lenient().when(orderRepository.save(any())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            if (o.getId() == null)
                o.setId(50L);
            return o;
        });
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("placeTableOrder — สั่งเข้าครัว")
    class PlaceOrder {

        @Test
        @DisplayName("ราคาจากตะกร้าทานในร้านที่ server คิด + ตัด stock เป็นชิ้น + ล้างเฉพาะตะกร้าทานในร้าน")
        void pricesFromDineInCart() {
            PricingService.PricedLine cake = new PricingService.PricedLine(
                    7L, "เค้กส้ม", "img", "cake", "1 ปอนด์ (8 ชิ้น)", 560.0, 8, 1, 560.0);
            when(pricingService.priceCart(EMAIL, CartMode.DINE_IN))
                    .thenReturn(new PricingService.PricedCart(List.of(cake), 560.0, 0.0, 560.0));
            when(dineInOrderRepository.save(any())).thenAnswer(inv -> {
                DineInOrderEntity o = inv.getArgument(0);
                o.setId(3L);
                return o;
            });

            DineInOrderEntity saved = service.placeTableOrder(EMAIL, " 5 ", DineInBillService.DineType.ALACARTE);

            assertThat(saved.getTableNo()).isEqualTo("5");
            assertThat(saved.getDineType()).isEqualTo("alacarte");
            assertThat(saved.getTotal()).isEqualTo(560.0);
            assertThat(saved.getOrdCode()).isEqualTo(new OrderCodeGenerator().generate(3L));
            ArgumentCaptor<DineInOrderItemEntity> item = ArgumentCaptor.forClass(DineInOrderItemEntity.class);
            verify(dineInOrderItemRepository).save(item.capture());
            assertThat(item.getValue().getQuantity()).isEqualTo(1);
            assertThat(item.getValue().getPrice()).isEqualTo(560.0);
            verify(stockService).decreaseStock(7L, 8);
            verify(cartService).clear(EMAIL, CartMode.DINE_IN);
        }

        @Test
        @DisplayName("ไม่ระบุโต๊ะ → 400 ไม่แตะตะกร้า")
        void missingTable_rejected() {
            assertThatThrownBy(() -> service.placeTableOrder(EMAIL, "  ", DineInBillService.DineType.ALACARTE))
                    .isInstanceOf(DineInBillService.BillException.class);
            verifyNoInteractions(pricingService, cartService);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("บุฟเฟต์ 100 นาที")
    class BuffetTime {

        private void cartWithOneItem() {
            PricingService.PricedLine line = new PricingService.PricedLine(
                    1L, "ชาไทย", "img", "drink", null, 45.0, 1, 1, 45.0);
            when(pricingService.priceCart(EMAIL, CartMode.DINE_IN))
                    .thenReturn(new PricingService.PricedCart(List.of(line), 45.0, 0.0, 45.0));
            when(dineInOrderRepository.save(any())).thenAnswer(inv -> {
                DineInOrderEntity o = inv.getArgument(0);
                if (o.getId() == null)
                    o.setId(9L);
                return o;
            });
        }

        @Test
        @DisplayName("ออเดอร์แรกเกิน 100 นาทีแล้ว → 409 ไม่แตะตะกร้า/stock")
        void over100Minutes_rejected() {
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(buffetOrder(1L, NOW.minusMinutes(100))));

            assertThatThrownBy(() -> service.placeTableOrder(EMAIL, "5", DineInBillService.DineType.BUFFET))
                    .isInstanceOfSatisfying(DineInBillService.BillException.class,
                            e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(pricingService, stockService, cartService);
        }

        @Test
        @DisplayName("ยังไม่ครบ 100 นาที → สั่งได้ นับจากออเดอร์แรก ไม่ใช่ออเดอร์ล่าสุด")
        void within100Minutes_allowed() {
            cartWithOneItem();
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(List.of(
                    buffetOrder(2L, NOW.minusMinutes(5)), buffetOrder(1L, NOW.minusMinutes(99))));

            DineInOrderEntity saved = service.placeTableOrder(EMAIL, "5", DineInBillService.DineType.BUFFET);

            assertThat(saved.getDineType()).isEqualTo("buffet");
            assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("ส่ง alacarte มากลางมื้อบุฟเฟต์ → ยังเป็นบุฟเฟต์และโดนจำกัดเวลา")
        void cannotSwitchToAlaCarteToDodgeLimit() {
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(buffetOrder(1L, NOW.minusMinutes(120))));

            assertThatThrownBy(() -> service.placeTableOrder(EMAIL, "5", DineInBillService.DineType.ALACARTE))
                    .isInstanceOf(DineInBillService.BillException.class);
        }

        @Test
        @DisplayName("à la carte ไม่จำกัดเวลา")
        void alaCarte_noLimit() {
            cartWithOneItem();
            DineInOrderEntity old = tableOrder(1L, "5", "completed", 50.0);
            old.setDineType("alacarte");
            old.setCreatedAt(NOW.minusHours(5));
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(List.of(old));

            assertThat(service.placeTableOrder(EMAIL, "5", DineInBillService.DineType.BUFFET).getDineType())
                    .isEqualTo("alacarte");
        }

        @Test
        @DisplayName("currentSession — สิ้นสุดเวลาสั่ง = ออเดอร์แรก + 100 นาที")
        void sessionEndsAt() {
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(buffetOrder(1L, NOW.minusMinutes(40))));

            DineInBillService.DiningSession s = service.currentSession(EMAIL, "5").orElseThrow();

            assertThat(s.orderingEndsAt()).isEqualTo(NOW.plusMinutes(60));
            assertThat(s.secondsLeftAt(NOW)).isEqualTo(60L * 60);
            assertThat(s.canOrderAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("เรียกบิล → ประเภทมาจากมื้อ (บุฟเฟต์) แม้ client ส่ง alacarte")
        void bill_usesRecordedType() {
            echoSaves();
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(buffetOrder(1L, NOW.minusMinutes(30))));

            OrderEntity bill = service.prepareBill(EMAIL, "5", DineInBillService.DineType.ALACARTE, 2,
                    CheckoutMethod.CARD);

            assertThat(bill.getDineType()).isEqualTo("buffet");
            assertThat(bill.getTotal()).isEqualTo(DineInBillService.BUFFET_PRICE_PER_PERSON * 2);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("prepareBill — เรียกเก็บบิล")
    class PrepareBill {

        @Test
        @DisplayName("à la carte → ยอดรวมออเดอร์ที่เสิร์ฟแล้ว ผูกออเดอร์ + คัดลอกรายการเป็นชิ้น")
        void alaCarte_sumsServedOrders() {
            echoSaves();
            DineInOrderEntity served = tableOrder(1L, "5", "completed", 120.0);
            DineInOrderEntity cooking = tableOrder(2L, "5", "preparing", 80.0);
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(List.of(served, cooking));
            DineInOrderItemEntity item = new DineInOrderItemEntity();
            item.setProductId(7L);
            item.setQuantity(1);
            item.setPrice(120.0);
            item.setSelectedOption("1 ปอนด์ (8 ชิ้น)");
            when(dineInOrderItemRepository.findByOrderId(1L)).thenReturn(List.of(item));

            OrderEntity bill = service.prepareBill(EMAIL, "5", DineInBillService.DineType.ALACARTE, 1,
                    CheckoutMethod.CARD);

            assertThat(bill.getTotal()).isEqualTo(120.0);
            assertThat(bill.getPaymentMethod()).isEqualTo(PaymentMethods.CARD);
            assertThat(bill.getPaymentStatus()).isEqualTo(PaymentStateService.AWAITING);
            assertThat(bill.getOrderType()).isEqualTo("dine-in");
            assertThat(served.getBillOrderId()).isEqualTo(50L);
            assertThat(cooking.getBillOrderId()).isNull();
            ArgumentCaptor<OrderItemEntity> copy = ArgumentCaptor.forClass(OrderItemEntity.class);
            verify(orderItemRepository).save(copy.capture());
            assertThat(copy.getValue().getQuantity()).isEqualTo(8);
        }

        @Test
        @DisplayName("บุฟเฟต์ → ราคาต่อคนของร้าน × จำนวนคน (ไม่รับราคาจาก client)")
        void buffet_usesServerPrice() {
            echoSaves();
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(tableOrder(1L, "5", "completed", 999.0)));

            OrderEntity bill = service.prepareBill(EMAIL, "5", DineInBillService.DineType.BUFFET, 3,
                    CheckoutMethod.TRANSFER);

            assertThat(bill.getTotal()).isEqualTo(DineInBillService.BUFFET_PRICE_PER_PERSON * 3);
            assertThat(bill.getBuffetPax()).isEqualTo(3);
            assertThat(bill.getPaymentMethod()).isEqualTo(PaymentMethods.PROMPTPAY_TRANSFER);
        }

        @Test
        @DisplayName("บุฟเฟต์จำนวนคนเกินโต๊ะ / ศูนย์ → 400")
        void buffet_invalidPax() {
            assertThatThrownBy(() -> service.prepareBill(EMAIL, "5", DineInBillService.DineType.BUFFET,
                    DineInBillService.MAX_BUFFET_PAX + 1, CheckoutMethod.CARD))
                    .isInstanceOf(DineInBillService.BillException.class);
            assertThatThrownBy(() -> service.prepareBill(EMAIL, "5", DineInBillService.DineType.BUFFET,
                    0, CheckoutMethod.CARD))
                    .isInstanceOf(DineInBillService.BillException.class);
            verifyNoInteractions(paymentReconciler);
        }

        @Test
        @DisplayName("แจ้งโอนบิลก่อนหน้าแล้ว รอตรวจ → 409 ไม่ออกบิลซ้อน")
        void verifyingBill_conflict() {
            OrderEntity verifying = new OrderEntity();
            verifying.setId(40L);
            verifying.setOrderType("dine-in");
            when(paymentReconciler.settleAwaiting(EMAIL, "dine-in")).thenReturn(Set.of());
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.VERIFYING))
                    .thenReturn(List.of(verifying));

            assertThatThrownBy(() -> service.prepareBill(EMAIL, "5", DineInBillService.DineType.ALACARTE, 1,
                    CheckoutMethod.PROMPTPAY))
                    .isInstanceOfSatisfying(DineInBillService.BillException.class,
                            e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("ไม่มีออเดอร์ที่เสิร์ฟแล้ว → 400")
        void nothingServed() {
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(tableOrder(1L, "5", "pending", 50.0)));

            assertThatThrownBy(() -> service.prepareBill(EMAIL, "5", DineInBillService.DineType.ALACARTE, 1,
                    CheckoutMethod.PROMPTPAY))
                    .isInstanceOfSatisfying(DineInBillService.BillException.class,
                            e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("เรียกบิลซ้ำ → ใช้บิลรอจ่ายแถวล่าสุดต่อ แถวเกินถูกลบ")
        void reusesLatestAwaitingBill() {
            echoSaves();
            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc(EMAIL))
                    .thenReturn(List.of(tableOrder(1L, "5", "completed", 100.0)));
            OrderEntity older = new OrderEntity();
            older.setId(10L);
            older.setOrderType("dine-in");
            OrderEntity latest = new OrderEntity();
            latest.setId(12L);
            latest.setOrderType("dine-in");
            latest.setOrdCode("ORD-KEEP");
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.VERIFYING))
                    .thenReturn(List.of());
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.AWAITING))
                    .thenReturn(List.of(older, latest));

            OrderEntity bill = service.prepareBill(EMAIL, "5", DineInBillService.DineType.ALACARTE, 1,
                    CheckoutMethod.PROMPTPAY);

            assertThat(bill).isSameAs(latest);
            assertThat(bill.getOrdCode()).isEqualTo("ORD-KEEP");
            verify(paymentStateService).discardUnpaid(eq(10L), any());
            verify(orderItemRepository).deleteByOrderId(12L);
        }
    }
}
