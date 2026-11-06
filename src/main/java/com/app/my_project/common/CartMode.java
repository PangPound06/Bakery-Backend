package com.app.my_project.common;

import java.util.Arrays;
import java.util.Optional;

/**
 * ตะกร้าของลูกค้าแยกเป็นสองใบตามช่องทาง — ค่าที่เก็บใน tb_cart.cart_mode
 *
 * ของที่หยิบใส่ตอนสั่งออนไลน์จะไม่โผล่ในตะกร้าตอนนั่งกินที่ร้าน และกลับกัน
 * ค่า requestValue ตรงกับ localStorage "orderMode" ของ frontend
 */
public enum CartMode {
    ONLINE("online"),
    DINE_IN("dinein");

    private final String value;

    CartMode(String value) {
        this.value = value;
    }

    /** ค่าที่เก็บในฐานข้อมูลและรับจาก query string ?mode= */
    public String value() {
        return value;
    }

    /** ไม่ส่งมา = ออนไลน์ (พฤติกรรมเดิมก่อนแยกตะกร้า) / ค่าที่ไม่รู้จัก = empty */
    public static Optional<CartMode> parse(String raw) {
        if (raw == null || raw.isBlank())
            return Optional.of(ONLINE);
        return Arrays.stream(values()).filter(m -> m.value.equalsIgnoreCase(raw.trim())).findFirst();
    }

    /** ตะกร้าที่ order ชนิดนี้ถูกสั่งมาจาก — บิลหน้าร้านใช้ตะกร้าทานในร้าน */
    public static CartMode forOrderType(String orderType) {
        return "dine-in".equals(orderType) ? DINE_IN : ONLINE;
    }
}
