package com.app.my_project.service;

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
 * Test TransferExpiryJob — ปิด order โอนพร้อมเพย์ที่ไม่แจ้งโอนภายในเวลา
 */
@ExtendWith(MockitoExtension.class)
class TransferExpiryJobTest {

    @Mock private PaymentStateService paymentStateService;

    @InjectMocks
    private TransferExpiryJob job;

    @Test
    @DisplayName("ค้นด้วยเวลาย้อนหลังตาม TRANSFER_PAY_WINDOW แล้วปิดทีละ order")
    void expiresEachStaleOrder() {
        when(paymentStateService.findUnsubmittedTransfers(any())).thenReturn(List.of(1L, 2L));

        LocalDateTime before = LocalDateTime.now().minus(PaymentStateService.TRANSFER_PAY_WINDOW);
        job.expireUnsubmittedTransfers();
        LocalDateTime after = LocalDateTime.now().minus(PaymentStateService.TRANSFER_PAY_WINDOW);

        verify(paymentStateService).findUnsubmittedTransfers(argThat(cutoff ->
                !cutoff.isBefore(before) && !cutoff.isAfter(after)));
        verify(paymentStateService).releaseOrder(eq(1L), eq(PaymentStateService.EXPIRED), any());
        verify(paymentStateService).releaseOrder(eq(2L), eq(PaymentStateService.EXPIRED), any());
    }

    @Test
    @DisplayName("order หนึ่งพัง → order ที่เหลือยังถูกปิดต่อ")
    void oneFailureDoesNotStopOthers() {
        when(paymentStateService.findUnsubmittedTransfers(any())).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("db")).when(paymentStateService)
                .releaseOrder(eq(1L), any(), any());

        assertThatCode(() -> job.expireUnsubmittedTransfers()).doesNotThrowAnyException();
        verify(paymentStateService).releaseOrder(eq(2L), eq(PaymentStateService.EXPIRED), any());
    }

    @Test
    @DisplayName("ฐานข้อมูลล่มตอนค้นหา → ไม่ throw ออกไปฆ่า scheduler")
    void queryFailureIsSwallowed() {
        when(paymentStateService.findUnsubmittedTransfers(any())).thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> job.expireUnsubmittedTransfers()).doesNotThrowAnyException();
        verify(paymentStateService, never()).releaseOrder(any(), any(), any());
        assertThat(PaymentStateService.TRANSFER_PAY_WINDOW.toMinutes()).isEqualTo(30);
    }
}
