package com.app.my_project.service;

import com.app.my_project.common.PaymentMethods;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.OrderRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PaymentReconciler — order ที่ Stripe ได้เงินแล้วต้องไม่ถูกเขียนทับ / ปล่อยคืน
 */
@ExtendWith(MockitoExtension.class)
class PaymentReconcilerTest {

    @Mock private StripeService stripeService;
    @Mock private PaymentStateService paymentStateService;
    @Mock private OrderRepository orderRepository;

    @InjectMocks
    private PaymentReconciler reconciler;

    private static final String EMAIL = "user@test.com";

    @BeforeEach
    void stripeEnabled() {
        lenient().when(stripeService.isEnabled()).thenReturn(true);
    }

    private static OrderEntity awaiting(long id, String type, String intentId) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setEmail(EMAIL);
        o.setOrderType(type);
        o.setPaymentStatus(PaymentStateService.AWAITING);
        o.setStripePaymentIntentId(intentId);
        return o;
    }

    private static PaymentIntent intent(String id, String status) {
        PaymentIntent pi = new PaymentIntent();
        pi.setId(id);
        pi.setStatus(status);
        return pi;
    }

    @Nested
    @DisplayName("refresh() — หน้าที่ poll สถานะ")
    class Refresh {

        @Test
        @DisplayName("Stripe ได้เงินแล้วแต่ webhook ยังไม่มา → mark paid")
        void succeeded_marksPaid() throws Exception {
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "succeeded"));

            var outcome = reconciler.refresh(awaiting(1L, "online", "pi_1"));

            assertThat(outcome).isEqualTo(PaymentReconciler.Outcome.PAID);
            verify(paymentStateService).markPaid(1L, "pi_1");
        }

        @Test
        @DisplayName("ยังรอสแกน → ไม่แตะอะไร ไม่ยกเลิก QR")
        void requiresAction_unchanged() throws Exception {
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "requires_action"));

            assertThat(reconciler.refresh(awaiting(1L, "online", "pi_1")))
                    .isEqualTo(PaymentReconciler.Outcome.UNPAID);
            verify(paymentStateService, never()).markPaid(anyLong(), anyString());
            verify(stripeService, never()).cancelIntent(anyString());
        }

        @Test
        @DisplayName("ถาม Stripe ไม่สำเร็จ → IN_FLIGHT (ไม่สรุปเอาเอง)")
        void stripeDown_inFlight() throws Exception {
            when(stripeService.retrieveIntent("pi_1")).thenThrow(new ApiConnectionException("down"));

            assertThat(reconciler.refresh(awaiting(1L, "online", "pi_1")))
                    .isEqualTo(PaymentReconciler.Outcome.IN_FLIGHT);
        }

        @Test
        @DisplayName("order จ่ายแล้ว → ไม่ถาม Stripe ซ้ำ")
        void alreadyPaid_skipsStripe() throws Exception {
            OrderEntity paid = awaiting(1L, "online", "pi_1");
            paid.setPaymentStatus(PaymentStateService.PAID);

            assertThat(reconciler.refresh(paid)).isEqualTo(PaymentReconciler.Outcome.PAID);
            verify(stripeService, never()).retrieveIntent(anyString());
        }
    }

    @Nested
    @DisplayName("settle() — ก่อนเริ่มจ่ายรอบใหม่ / ตอนกดยกเลิก")
    class Settle {

        @Test
        @DisplayName("ยังไม่จ่าย → ยกเลิก intent แล้วคืน UNPAID")
        void unpaid_cancelsIntent() throws Exception {
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "requires_action"));
            when(stripeService.cancelIntent("pi_1")).thenReturn(true);

            assertThat(reconciler.settle(awaiting(1L, "online", "pi_1")))
                    .isEqualTo(PaymentReconciler.Outcome.UNPAID);
        }

        @Test
        @DisplayName("ลูกค้าจ่ายพอดีตอนยกเลิก → ยกเลิกไม่ได้ ถามซ้ำแล้ว mark paid")
        void paidDuringCancel_marksPaid() throws Exception {
            when(stripeService.retrieveIntent("pi_1"))
                    .thenReturn(intent("pi_1", "requires_action"))
                    .thenReturn(intent("pi_1", "succeeded"));
            when(stripeService.cancelIntent("pi_1")).thenReturn(false);

            assertThat(reconciler.settle(awaiting(1L, "online", "pi_1")))
                    .isEqualTo(PaymentReconciler.Outcome.PAID);
            verify(paymentStateService).markPaid(1L, "pi_1");
        }

        @Test
        @DisplayName("กำลังตัดเงิน → IN_FLIGHT ไม่ยกเลิก")
        void processing_inFlight() throws Exception {
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "processing"));

            assertThat(reconciler.settle(awaiting(1L, "online", "pi_1")))
                    .isEqualTo(PaymentReconciler.Outcome.IN_FLIGHT);
            verify(stripeService, never()).cancelIntent(anyString());
        }

        @Test
        @DisplayName("ยังไม่ได้สร้าง intent → UNPAID โดยไม่เรียก Stripe")
        void noIntent_unpaid() throws Exception {
            assertThat(reconciler.settle(awaiting(1L, "online", null)))
                    .isEqualTo(PaymentReconciler.Outcome.UNPAID);
            verify(stripeService, never()).retrieveIntent(anyString());
        }
    }

    @Nested
    @DisplayName("discardIfUnpaid() / onIntentEnded() — ลบรอบที่ไม่ได้เงิน")
    class Discard {

        @Test
        @DisplayName("ยกเลิก intent ได้ → ลบ order")
        void unpaid_discards() throws Exception {
            OrderEntity o = awaiting(1L, "online", "pi_1");
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "requires_action"));
            when(stripeService.cancelIntent("pi_1")).thenReturn(true);
            when(paymentStateService.discardUnpaid(1L, "หมดเวลา")).thenReturn(true);

            assertThat(reconciler.discardIfUnpaid(o, "หมดเวลา")).isTrue();
        }

        @Test
        @DisplayName("จ่ายแล้วพอดี → ไม่ลบ")
        void paid_kept() throws Exception {
            OrderEntity o = awaiting(1L, "online", "pi_1");
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "succeeded"));

            assertThat(reconciler.discardIfUnpaid(o, "หมดเวลา")).isFalse();
            verify(paymentStateService, never()).discardUnpaid(anyLong(), anyString());
        }

        @Test
        @DisplayName("intent ถูกยกเลิกไปแล้ว → ลบเลย ไม่สั่งยกเลิกซ้ำ")
        void alreadyCanceled_noSecondCancel() throws Exception {
            OrderEntity o = awaiting(1L, "online", "pi_1");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "canceled"));

            reconciler.onIntentEnded(1L, "pi_1", true, "ยกเลิก");

            verify(stripeService, never()).cancelIntent(anyString());
            verify(paymentStateService).discardUnpaid(1L, "ยกเลิก");
        }

        @Test
        @DisplayName("event ของ intent เก่า (order ถูกใช้ต่อด้วย intent ใหม่) → ไม่แตะ")
        void staleIntent_ignored() {
            when(orderRepository.findById(1L)).thenReturn(Optional.of(awaiting(1L, "online", "pi_new")));

            reconciler.onIntentEnded(1L, "pi_old", true, "ยกเลิก");

            verifyNoInteractions(stripeService);
            verify(paymentStateService, never()).discardUnpaid(anyLong(), anyString());
        }

        @Test
        @DisplayName("บัตรถูกปฏิเสธ → เก็บ order ไว้ให้ลูกค้าลองบัตรใบอื่น")
        void cardDeclined_kept() {
            OrderEntity o = awaiting(1L, "online", "pi_1");
            o.setPaymentMethod(PaymentMethods.CARD);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(o));

            reconciler.onIntentEnded(1L, "pi_1", false, "card_declined");

            verifyNoInteractions(stripeService);
            verify(paymentStateService, never()).discardUnpaid(anyLong(), anyString());
        }

        @Test
        @DisplayName("QR PromptPay หมดอายุ (payment_failed) → ลบ")
        void promptPayFailed_discards() throws Exception {
            OrderEntity o = awaiting(1L, "online", "pi_1");
            o.setPaymentMethod(PaymentMethods.QR_PROMPTPAY);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
            when(stripeService.retrieveIntent("pi_1")).thenReturn(intent("pi_1", "requires_payment_method"));
            when(stripeService.cancelIntent("pi_1")).thenReturn(true);

            reconciler.onIntentEnded(1L, "pi_1", false, "expired");

            verify(paymentStateService).discardUnpaid(1L, "expired");
        }
    }

    @Nested
    @DisplayName("settleAwaiting() — คืน id ที่ห้ามเขียนทับ")
    class SettleAwaiting {

        @Test
        @DisplayName("ล็อก order ที่จ่ายแล้ว และไม่แตะ order ของช่องทางอื่น")
        void locksPaidAndIgnoresOtherChannel() throws Exception {
            OrderEntity paidOnline = awaiting(1L, "online", "pi_paid");
            OrderEntity unpaidOnline = awaiting(2L, "online", "pi_open");
            OrderEntity dineInBill = awaiting(3L, "dine-in", "pi_bill");
            when(orderRepository.findByEmailAndPaymentStatus(EMAIL, PaymentStateService.AWAITING))
                    .thenReturn(List.of(paidOnline, unpaidOnline, dineInBill));
            when(stripeService.retrieveIntent("pi_paid")).thenReturn(intent("pi_paid", "succeeded"));
            when(stripeService.retrieveIntent("pi_open")).thenReturn(intent("pi_open", "requires_action"));
            when(stripeService.cancelIntent("pi_open")).thenReturn(true);

            Set<Long> locked = reconciler.settleAwaiting(EMAIL, "online");

            assertThat(locked).containsExactly(1L);
            verify(paymentStateService).markPaid(1L, "pi_paid");
            verify(stripeService, never()).retrieveIntent("pi_bill");
        }
    }
}
