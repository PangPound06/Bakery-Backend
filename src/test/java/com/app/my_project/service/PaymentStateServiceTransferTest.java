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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test PaymentStateService — การโอนพร้อมเพย์ที่แอดมินยืนยันเอง
 *
 * awaiting_payment → (ลูกค้าแจ้งโอน) awaiting_verification → (แอดมิน) paid | failed
 */
@ExtendWith(MockitoExtension.class)
class PaymentStateServiceTransferTest {

    private static final String EMAIL = "cus@test.com";
    private static final String SLIP = "https://res.cloudinary.com/demo/image/upload/slip.png";
    private static final String REF = "slip:abc123";

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private StockService stockService;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PaymentStateService service;

    private OrderEntity transferOrder(String paymentStatus) {
        OrderEntity o = new OrderEntity();
        o.setId(1L);
        o.setEmail(EMAIL);
        o.setOrdCode("ORD1");
        o.setOrderType("online");
        o.setOrderStatus("pending");
        o.setPaymentMethod(PaymentMethods.PROMPTPAY_TRANSFER);
        o.setPaymentStatus(paymentStatus);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
        return o;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("submitTransfer — ลูกค้ากดแจ้งโอนพร้อมสลิป")
    class Submit {

        @Test
        @DisplayName("รอโอน → รอตรวจสอบ + เก็บสลิปและ reference + ล้างตะกร้า (ยังไม่ใช่ paid)")
        void awaiting_becomesVerifying() {
            OrderEntity order = transferOrder(PaymentStateService.AWAITING);

            assertThat(service.submitTransfer(1L, EMAIL, SLIP, REF)).isEqualTo(PaymentStateService.TransferResult.OK);

            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStateService.VERIFYING);
            assertThat(order.getSlipImage()).isEqualTo(SLIP);
            assertThat(order.getPaymentId()).isEqualTo(REF);
            verify(orderRepository).save(order);
            verify(jdbcTemplate).update("DELETE FROM tb_cart WHERE email = ?", EMAIL);
        }

        @Test
        @DisplayName("ส่งสลิปใหม่ระหว่างรอตรวจได้")
        void verifying_canResubmit() {
            OrderEntity order = transferOrder(PaymentStateService.VERIFYING);

            assertThat(service.submitTransfer(1L, EMAIL, SLIP, REF)).isEqualTo(PaymentStateService.TransferResult.OK);
            assertThat(order.getSlipImage()).isEqualTo(SLIP);
        }

        @Test
        @DisplayName("สลิปใบเดียวกันถูกใช้กับ order อื่นแล้ว → DUPLICATE_SLIP")
        void duplicateSlip_rejected() {
            OrderEntity order = transferOrder(PaymentStateService.AWAITING);
            when(orderRepository.existsByPaymentIdAndIdNot(REF, 1L)).thenReturn(true);

            assertThat(service.checkSubmittable(1L, EMAIL, REF))
                    .isEqualTo(PaymentStateService.TransferResult.DUPLICATE_SLIP);
            assertThat(service.submitTransfer(1L, EMAIL, SLIP, REF))
                    .isEqualTo(PaymentStateService.TransferResult.DUPLICATE_SLIP);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStateService.AWAITING);
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("order ของคนอื่น → NOT_FOUND ไม่แตะอะไร")
        void otherCustomer_notFound() {
            transferOrder(PaymentStateService.AWAITING);

            assertThat(service.submitTransfer(1L, "other@test.com", SLIP, REF))
                    .isEqualTo(PaymentStateService.TransferResult.NOT_FOUND);
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("หมดเวลาไปแล้ว / จ่ายผ่าน Stripe → แจ้งโอนไม่ได้")
        void wrongStateOrMethod() {
            OrderEntity order = transferOrder(PaymentStateService.EXPIRED);
            assertThat(service.submitTransfer(1L, EMAIL, SLIP, REF))
                    .isEqualTo(PaymentStateService.TransferResult.WRONG_STATE);

            order.setPaymentStatus(PaymentStateService.AWAITING);
            order.setPaymentMethod(PaymentMethods.QR_PROMPTPAY);
            assertThat(service.submitTransfer(1L, EMAIL, SLIP, REF))
                    .isEqualTo(PaymentStateService.TransferResult.NOT_TRANSFER);
            verify(orderRepository, never()).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("confirmTransfer / rejectTransfer — แอดมินตัดสิน")
    class Decide {

        @Test
        @DisplayName("ยืนยัน → paid + บันทึกชื่อแอดมินใน note")
        void confirm_marksPaid() {
            OrderEntity order = transferOrder(PaymentStateService.VERIFYING);

            assertThat(service.confirmTransfer(1L, "boss@empbakery.com"))
                    .isEqualTo(PaymentStateService.TransferResult.OK);

            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStateService.PAID);
            assertThat(order.getNote()).contains("boss@empbakery.com");
            assertThat(order.getStripePaymentIntentId()).isNull();
        }

        @Test
        @DisplayName("ลูกค้าโอนแล้วลืมกดแจ้ง → แอดมินยืนยันจากสถานะรอโอนได้เลย")
        void confirm_fromAwaiting() {
            OrderEntity order = transferOrder(PaymentStateService.AWAITING);

            assertThat(service.confirmTransfer(1L, "boss@empbakery.com"))
                    .isEqualTo(PaymentStateService.TransferResult.OK);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStateService.PAID);
        }

        @Test
        @DisplayName("ยืนยันซ้ำ / order หมดเวลา → WRONG_STATE")
        void confirm_wrongState() {
            transferOrder(PaymentStateService.PAID);
            assertThat(service.confirmTransfer(1L, "boss@empbakery.com"))
                    .isEqualTo(PaymentStateService.TransferResult.WRONG_STATE);
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("ปฏิเสธ → failed + cancelled + คืน stock")
        void reject_releasesStock() {
            OrderEntity order = transferOrder(PaymentStateService.VERIFYING);
            OrderItemEntity item = new OrderItemEntity();
            item.setProductId(10L);
            item.setQuantity(2);
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(item));

            assertThat(service.rejectTransfer(1L, "ยอดไม่ตรง", "boss@empbakery.com"))
                    .isEqualTo(PaymentStateService.TransferResult.OK);

            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStateService.FAILED);
            assertThat(order.getOrderStatus()).isEqualTo("cancelled");
            assertThat(order.getNote()).contains("ยอดไม่ตรง");
            verify(stockService).batchIncreaseStock(argThat(list -> list.size() == 1));
        }

        @Test
        @DisplayName("ไม่มี order → NOT_FOUND")
        void notFound() {
            when(orderRepository.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.confirmTransfer(9L, "a")).isEqualTo(PaymentStateService.TransferResult.NOT_FOUND);
            assertThat(service.rejectTransfer(9L, null, "a")).isEqualTo(PaymentStateService.TransferResult.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("findUnsubmittedTransfers — ค้นเฉพาะโอนพร้อมเพย์ที่ยังรอโอน")
    void findUnsubmitted_queriesAwaitingTransfersOnly() {
        LocalDateTime cutoff = LocalDateTime.now();
        OrderEntity stale = new OrderEntity();
        stale.setId(7L);
        when(orderRepository.findByPaymentMethodAndPaymentStatusAndCreatedAtBefore(
                PaymentMethods.PROMPTPAY_TRANSFER, PaymentStateService.AWAITING, cutoff))
                .thenReturn(List.of(stale));

        assertThat(service.findUnsubmittedTransfers(cutoff)).containsExactly(7L);
    }
}
