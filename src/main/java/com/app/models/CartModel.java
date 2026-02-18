package com.app.models;

public class CartModel {
    private Integer id;
    private String email;
    private String productName;
    private Integer quantity;
    private Integer price;
    private Integer subtotal;
    private String category;
    private String image;

    // Constructor แบบเต็ม (มี id)
    public CartModel(Integer id, String email, String productName, Integer quantity, 
                     Integer price, String category, String image) {
        this.id = id;
        this.email = email;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
        this.category = category;
        this.image = image;
        this.subtotal = (quantity != null && price != null) ? quantity * price : 0;  // ✅ คำนวณถูกต้อง
    }

    // Constructor แบบไม่มี id (สำหรับ insert)
    public CartModel(String email, String productName, Integer quantity, 
                     Integer price, String category, String image) {
        this.email = email;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
        this.category = category;
        this.image = image;
        this.subtotal = (quantity != null && price != null) ? quantity * price : 0;  // ✅ คำนวณถูกต้อง
    }

    // Constructor เปล่า
    public CartModel() {
    }

    // Getters
    public Integer getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getProductName() {
        return productName;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getPrice() {
        return price;
    }

    public Integer getSubtotal() {
        if (subtotal != null) {
            return subtotal;
        }
        return (quantity != null && price != null) ? quantity * price : 0;
    }

    public String getCategory() {
        return category;
    }

    public String getImage() {
        return image;
    }

    // Setters
    public void setId(Integer id) {
        this.id = id;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
        if (this.price != null) {
            this.subtotal = quantity * this.price;
        }
    }

    public void setPrice(Integer price) {
        this.price = price;
        if (this.quantity != null) {
            this.subtotal = this.quantity * price;
        }
    }

    public void setSubtotal(Integer subtotal) {
        this.subtotal = subtotal;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setImage(String image) {
        this.image = image;
    }

    @Override
    public String toString() {
        return "CartModel{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", productName='" + productName + '\'' +
                ", quantity=" + quantity +
                ", price=" + price +
                ", subtotal=" + getSubtotal() +
                ", category='" + category + '\'' +
                ", image='" + image + '\'' +
                '}';
    }
}