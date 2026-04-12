package com.app.my_project.models;

public class ProductModel {
    private Long id;
    private String name;
    private Double price;
    private String category; // ยังคงส่ง slug string ให้ Frontend
    private Long categoryId; // ✅ เพิ่ม
    private String categoryName; // ✅ เพิ่ม
    private String categoryIcon; // ✅ เพิ่ม
    private String image;
    private String type;
    private String description;
    private Long stockQuantity;
    private Boolean isAvailable;
    private String options;

    // Constructor ใหม่
    public ProductModel(Long id, String name, Double price, String category,
            Long categoryId, String categoryName, String categoryIcon,
            String image, String type, String description,
            Long stockQuantity, Boolean isAvailable, String options) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.category = category;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.categoryIcon = categoryIcon;
        this.image = image;
        this.type = type;
        this.description = description;
        this.stockQuantity = stockQuantity;
        this.isAvailable = isAvailable;
        this.options = options;
    }

    public ProductModel() {
    }

    // Getters & Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryIcon() {
        return categoryIcon;
    }

    public void setCategoryIcon(String categoryIcon) {
        this.categoryIcon = categoryIcon;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Long stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public Boolean getIsAvailable() {
        return isAvailable;
    }

    public void setIsAvailable(Boolean isAvailable) {
        this.isAvailable = isAvailable;
    }

    public String getOptions() {
        return options;
    }

    public void setOptions(String options) {
        this.options = options;
    }
}