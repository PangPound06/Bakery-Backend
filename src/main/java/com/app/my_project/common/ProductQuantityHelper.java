package com.app.my_project.common;

import org.springframework.stereotype.Component;

/**
 * Helper สำหรับแปลง quantity ระหว่าง "ชิ้น" (raw) และ "ออเดอร์" (display)
 *
 * Business rule:
 *  - cake 1 ปอนด์ = 8 ชิ้น → display 1 ออเดอร์ (multiplier = 8)
 *  - cake 2 ปอนด์ = 16 ชิ้น → display 1 ออเดอร์ (multiplier = 16)
 *  - สินค้าทั่วไป → display = raw (multiplier = 1)
 *
 * เหตุผลที่แยกออกมา:
 *  - test ได้ง่ายแบบ pure logic (ไม่ต้อง mock อะไร)
 *  - logic นี้กระจายในหลาย method ของ OrderController เดิม → DRY
 */
@Component
public class ProductQuantityHelper {

    /** raw quantity (จำนวนชิ้น) → display quantity (จำนวนออเดอร์) */
    public int toDisplayQty(int rawQuantity, String selectedOption) {
        int multiplier = getMultiplier(selectedOption);
        return rawQuantity / multiplier;
    }

    /** display quantity (จำนวนออเดอร์) → raw quantity (จำนวนชิ้น) */
    public int toRawQty(int displayQuantity, String selectedOption) {
        int multiplier = getMultiplier(selectedOption);
        return displayQuantity * multiplier;
    }

    /** จำนวนชิ้นต่อ 1 ออเดอร์ ตาม option */
    public int getMultiplier(String selectedOption) {
        if (selectedOption == null) return 1;
        if (selectedOption.contains("2 ปอนด์")) return 16;
        if (selectedOption.contains("1 ปอนด์")) return 8;
        return 1;
    }
}