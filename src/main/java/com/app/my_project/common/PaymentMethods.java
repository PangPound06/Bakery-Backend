package com.app.my_project.common;

/**
 * ค่าที่เก็บใน tb_orders.payment_method
 *
 * frontend ใช้ค่าเหล่านี้แสดงชื่อวิธีชำระเงิน — เปลี่ยนค่าเดิมไม่ได้ เพิ่มได้อย่างเดียว
 */
public final class PaymentMethods {

    /** PromptPay ผ่าน Stripe — ระบบยืนยันเองจาก webhook */
    public static final String QR_PROMPTPAY = "qr_promptpay";

    /** บัตรเครดิต/เดบิตผ่าน Stripe */
    public static final String CARD = "card";

    /** เงินสดหน้าร้าน (POS) */
    public static final String CASH = "cash";

    /** โอนเข้าพร้อมเพย์ของร้านโดยตรง — แอดมินตรวจยอดในแอปธนาคารแล้วกดยืนยันเอง */
    public static final String PROMPTPAY_TRANSFER = "promptpay_transfer";

    private PaymentMethods() {
    }

    public static boolean isPromptPayTransfer(String method) {
        return PROMPTPAY_TRANSFER.equals(method);
    }
}
