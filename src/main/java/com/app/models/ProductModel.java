package com.app.models;

public class ProductModel {
    private Long id;
    private String name;
    private Long price;
    private String category;
    private String image;
    private String type;
    private String description;
    private Long stockQuantity;
    private Boolean isAvailable;

    // Constructor แบบเต็ม
    public ProductModel(Long id, String name, Long price, String category,
            String image, String type, String description,
            Long stockQuantity, Boolean isAvailable) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.category = category;
        this.image = image;
        this.type = type;
        this.description = description;
        this.stockQuantity = stockQuantity;
        this.isAvailable = isAvailable;
    }

    // Constructor เปล่า
    public ProductModel() {
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getPrice() {
        return price;
    }

    public String getCategory() {
        return category;
    }

    public String getImage() {
        return image;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public Long getStockQuantity() {
        return stockQuantity;
    }

    public Boolean getIsAvailable() {
        return isAvailable;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPrice(Long price) {
        this.price = price;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStockQuantity(Long stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public void setIsAvailable(Boolean isAvailable) {
        this.isAvailable = isAvailable;
    }
}