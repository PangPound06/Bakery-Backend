package com.app.my_project.common;

import org.springframework.stereotype.Component;

/**
 * Generator + validator ของ Order Code
 *
 * Format: ORD<6-digit hash><id>
 *  - hash = (id * 104729) % 1000000 — pseudo-unique distribution
 *  - ตัวสุดท้ายเป็น id จริง → reverse engineer ได้ในกรณี ordCode หายจาก DB
 *
 * ตัวอย่าง:
 *  - id=1 → ORD104729 1 = "ORD1047291"
 *  - id=10 → ORD047290 10 = "ORD04729010"
 *
 * เหตุผลที่แยกออกมา:
 *  - logic นี้ซ้ำใน 3 ที่ของ OrderController เดิม (createOrder + backfill + searchByOrderId)
 *  - test ได้ง่าย (pure logic)
 */
@Component
public class OrderCodeGenerator {

    private static final long HASH_MULTIPLIER = 104729L;
    private static final long HASH_MODULO = 1000000L;

    public String generate(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        long hash = (orderId * HASH_MULTIPLIER) % HASH_MODULO;
        return "ORD" + String.format("%06d", hash) + orderId;
    }

    /**
     * พยายาม extract orderId จาก ordCode
     * - คืน null ถ้า format ผิด หรือ hash ไม่ตรง
     *
     * Format: ORD<6-digit><id> — id อยู่หลังตำแหน่งที่ 9 (3 + 6)
     */
    public Long extractOrderId(String ordCode) {
        if (ordCode == null) return null;
        String code = ordCode.toUpperCase().trim();
        if (!code.startsWith("ORD") || code.length() <= 9) return null;

        try {
            Long extractedId = Long.parseLong(code.substring(9));
            // validate ว่า generate กลับแล้วได้ code เดิม (กัน false positive)
            return generate(extractedId).equalsIgnoreCase(code) ? extractedId : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}