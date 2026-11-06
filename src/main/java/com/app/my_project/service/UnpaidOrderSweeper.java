package com.app.my_project.service;

import com.app.my_project.entity.OrderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * เก็บกวาดรอบจ่ายเงินที่ลูกค้าเปิดทิ้งไว้ — ปิด intent กับ Stripe คืน stock แล้วลบ order ทิ้ง
 *
 * ครอบคลุมกรณีที่ไม่มีใครบอกระบบ:
 * - โอนพร้อมเพย์ที่ไม่ได้แจ้งโอนภายในเวลา (ไม่มี webhook)
 * - ฟอร์มบัตรที่ปิดหน้าไปเฉยๆ (intent บัตรไม่หมดอายุเอง)
 * - QR ของ Stripe ที่ webhook ไม่มา (เช่น localhost ไม่ได้เปิด stripe listen)
 *
 * ถ้าไม่มีงานนี้ ของจะถูกจองค้างจนขายไม่ได้ และ order ค้างจ่ายจะกองอยู่ในฐานข้อมูล
 */
@Component
public class UnpaidOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderSweeper.class);

    private final PaymentStateService paymentStateService;
    private final PaymentReconciler paymentReconciler;

    public UnpaidOrderSweeper(PaymentStateService paymentStateService, PaymentReconciler paymentReconciler) {
        this.paymentStateService = paymentStateService;
        this.paymentReconciler = paymentReconciler;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    public void sweep() {
        List<OrderEntity> stale;
        try {
            stale = paymentStateService.findAbandoned(LocalDateTime.now().minus(PaymentStateService.UNPAID_TTL));
        } catch (RuntimeException e) {
            log.error("ค้นหา order ค้างจ่ายไม่สำเร็จ", e);
            return;
        }

        // ทีละ order คนละ transaction — order หนึ่งพังไม่ทำให้ order อื่นค้าง
        int removed = 0;
        for (OrderEntity order : stale) {
            try {
                if (paymentReconciler.discardIfUnpaid(order, "ไม่ชำระเงินภายในเวลา"))
                    removed++;
            } catch (RuntimeException e) {
                log.error("เก็บกวาด order {} ไม่สำเร็จ", order.getId(), e);
            }
        }
        if (removed > 0) {
            log.info("⏰ ลบรอบจ่ายเงินที่หมดเวลา {} รายการ", removed);
        }
    }
}
