package com.app.my_project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tb_purchase_orders")
public class PurchaseOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "po_code", unique = true)
    private String poCode;

    @Column(name = "item_name")
    private String itemName;

    @Column(name = "item_qty")
    private Integer itemQty;

    @Column(name = "item_unit")
    private String itemUnit;

    @Column(name = "item_price")
    private Double itemPrice;

    private Double total;

    @Column(name = "po_status")
    private String status = "pending";

    @Column(name = "payment_status")
    private String paymentStatus = "unpaid";

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // ===== Getters & Setters =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public String getPoCode() { return poCode; }
    public void setPoCode(String poCode) { this.poCode = poCode; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public Integer getItemQty() { return itemQty; }
    public void setItemQty(Integer itemQty) { this.itemQty = itemQty; }

    public String getItemUnit() { return itemUnit; }
    public void setItemUnit(String itemUnit) { this.itemUnit = itemUnit; }

    public Double getItemPrice() { return itemPrice; }
    public void setItemPrice(Double itemPrice) { this.itemPrice = itemPrice; }

    public Double getTotal() { return total; }
    public void setTotal(Double total) { this.total = total; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}