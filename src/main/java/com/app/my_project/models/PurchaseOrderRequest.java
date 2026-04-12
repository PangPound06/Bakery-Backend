package com.app.my_project.models;

import java.util.List;

public class PurchaseOrderRequest {

    private Long supplierId;
    private String supplierName;
    private List<POItem> items;
    private String note;

    public static class POItem {
        private String name;
        private Integer qty;
        private String unit;
        private Double price;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Integer getQty() { return qty; }
        public void setQty(Integer qty) { this.qty = qty; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }

        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }
    }

    // Getters & Setters
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public List<POItem> getItems() { return items; }
    public void setItems(List<POItem> items) { this.items = items; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}