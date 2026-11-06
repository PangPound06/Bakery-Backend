package com.app.my_project.common;

import java.util.Arrays;
import java.util.Optional;

/**
 * วิธีชำระเงินที่ลูกค้าเลือก (หน้า checkout ออนไลน์ และหน้าเรียกเก็บบิลในร้าน)
 *
 * requestValue = ค่าที่ frontend ส่งมา / storedValue = ค่าที่เก็บใน tb_orders.payment_method
 */
public enum CheckoutMethod {
    /** QR PromptPay ของ Stripe — ระบบยืนยันเองจาก webhook */
    PROMPTPAY("promptpay", PaymentMethods.QR_PROMPTPAY, true),
    /** บัตรเครดิต/เดบิตผ่าน Stripe */
    CARD("card", PaymentMethods.CARD, true),
    /** โอนเข้าพร้อมเพย์ของร้านตรง แอดมินตรวจยอดแล้วกดยืนยันเอง */
    TRANSFER("transfer", PaymentMethods.PROMPTPAY_TRANSFER, false);

    private final String requestValue;
    private final String storedValue;
    private final boolean viaStripe;

    CheckoutMethod(String requestValue, String storedValue, boolean viaStripe) {
        this.requestValue = requestValue;
        this.storedValue = storedValue;
        this.viaStripe = viaStripe;
    }

    public String requestValue() {
        return requestValue;
    }

    public String storedValue() {
        return storedValue;
    }

    public boolean viaStripe() {
        return viaStripe;
    }

    public static Optional<CheckoutMethod> parse(String raw) {
        if (raw == null)
            return Optional.empty();
        return Arrays.stream(values()).filter(m -> m.requestValue.equalsIgnoreCase(raw.trim())).findFirst();
    }
}
