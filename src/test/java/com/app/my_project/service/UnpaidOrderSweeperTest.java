package com.app.my_project.service;

import com.app.my_project.entity.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test UnpaidOrderSweeper — ลบรอบจ่ายเงินที่ค้างเกินเวลา
 */
@ExtendWith(MockitoExtension.class)
class UnpaidOrderSweeperTest {

    @Mock private PaymentStateService paymentStateService;
    @Mock private PaymentReconciler paymentReconciler;

    @InjectMocks
    private UnpaidOrderSweeper sweeper;

    private static OrderEntity order(long id) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        return o;
    }

    @Test
    @DisplayName("ค้นด้วยเวลาย้อนหลังตาม UNPAID_TTL แล้วปิดกับ Stripe + ลบทีละ order")
    void discardsEachStaleOrder() {
        OrderEntity a = order(1L);
        OrderEntity b = order(2L);
        when(paymentStateService.findAbandoned(any())).thenReturn(List.of(a, b));

        LocalDateTime before = LocalDateTime.now().minus(PaymentStateService.UNPAID_TTL);
        sweeper.sweep();
        LocalDateTime after = LocalDateTime.now().minus(PaymentStateService.UNPAID_TTL);

        verify(paymentStateService).findAbandoned(argThat(cutoff ->
                !cutoff.isBefore(before) && !cutoff.isAfter(after)));
        verify(paymentReconciler).discardIfUnpaid(eq(a), any());
        verify(paymentReconciler).discardIfUnpaid(eq(b), any());
    }

    @Test
    @DisplayName("order หนึ่งพัง → order ที่เหลือยังถูกเก็บกวาดต่อ")
    void oneFailureDoesNotStopOthers() {
        OrderEntity a = order(1L);
        OrderEntity b = order(2L);
        when(paymentStateService.findAbandoned(any())).thenReturn(List.of(a, b));
        when(paymentReconciler.discardIfUnpaid(eq(a), any())).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
        verify(paymentReconciler).discardIfUnpaid(eq(b), any());
    }

    @Test
    @DisplayName("ฐานข้อมูลล่มตอนค้นหา → ไม่ throw ออกไปฆ่า scheduler")
    void queryFailureIsSwallowed() {
        when(paymentStateService.findAbandoned(any())).thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
        verifyNoInteractions(paymentReconciler);
        assertThat(PaymentStateService.UNPAID_TTL.toMinutes()).isEqualTo(30);
    }
}
