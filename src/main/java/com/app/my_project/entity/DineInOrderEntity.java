package com.app.my_project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tb_dinein_orders", indexes = {
        @Index(name = "idx_dinein_email", columnList = "email"),
        @Index(name = "idx_dinein_order_status", columnList = "order_status"),
        @Index(name = "idx_dinein_created_at", columnList = "created_at")
})
public class DineInOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String tableNo;
    private String orderStatus = "pending";
    private Double total;
    private Double subtotal;
    private String ordCode;
    private LocalDateTime createdAt;
    private String slipImage;
    private String paymentStatus;

    /**
     * id ของบิลรวมใน tb_orders ที่ออเดอร์นี้ถูกรวมเข้าไป
     *
     * ต้องมีเส้นเชื่อมนี้ ไม่งั้นตอน webhook แจ้งว่าบิลจ่ายแล้ว
     * จะหาไม่เจอว่าต้องไปปิดออเดอร์โต๊ะไหนบ้าง
     */
    @Column(name = "bill_order_id")
    private Long billOrderId;

    // Getters & Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getTableNo() {
        return tableNo;
    }

    public void setTableNo(String tableNo) {
        this.tableNo = tableNo;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Double getTotal() {
        return total;
    }

    public void setTotal(Double total) {
        this.total = total;
    }

    public Double getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(Double subtotal) {
        this.subtotal = subtotal;
    }

    public String getOrdCode() {
        return ordCode;
    }

    public void setOrdCode(String ordCode) {
        this.ordCode = ordCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getSlipImage() {
        return slipImage;
    }

    public void setSlipImage(String slipImage) {
        this.slipImage = slipImage;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public Long getBillOrderId() {
        return billOrderId;
    }

    public void setBillOrderId(Long billOrderId) {
        this.billOrderId = billOrderId;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }
}