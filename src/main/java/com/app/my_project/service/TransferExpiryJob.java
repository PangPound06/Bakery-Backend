package com.app.my_project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ปิด order โอนพร้อมเพย์ที่ลูกค้าเปิด QR ไว้แต่ไม่แจ้งโอนภายในเวลา แล้วคืน stock ที่จองไว้
 *
 * Stripe มี webhook บอกว่า QR หมดอายุ แต่การโอนเข้าบัญชีร้านตรงไม่มีใครบอก
 * ถ้าไม่มีงานนี้ ของจะถูกจองค้างไปเรื่อยๆ จนขายไม่ได้
 */
@Component
public class TransferExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(TransferExpiryJob.class);

    private final PaymentStateService paymentStateService;

    public TransferExpiryJob(PaymentStateService paymentStateService) {
        this.paymentStateService = paymentStateService;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    public void expireUnsubmittedTransfers() {
        List<Long> stale;
        try {
            stale = paymentStateService.findUnsubmittedTransfers(
                    LocalDateTime.now().minus(PaymentStateService.TRANSFER_PAY_WINDOW));
        } catch (RuntimeException e) {
            log.error("ค้นหา order โอนพร้อมเพย์ที่หมดเวลาไม่สำเร็จ", e);
            return;
        }

        // ทีละ order คนละ transaction — order หนึ่งพังไม่ทำให้ order อื่นค้าง
        for (Long orderId : stale) {
            try {
                paymentStateService.releaseOrder(orderId, PaymentStateService.EXPIRED,
                        "ยกเลิกอัตโนมัติ: ไม่ได้แจ้งโอนภายในเวลา");
            } catch (RuntimeException e) {
                log.error("ปิด order โอนพร้อมเพย์ {} ที่หมดเวลาไม่สำเร็จ", orderId, e);
            }
        }
        if (!stale.isEmpty()) {
            log.info("⏰ ปิด order โอนพร้อมเพย์ที่หมดเวลา {} รายการ", stale.size());
        }
    }
}
