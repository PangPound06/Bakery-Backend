package com.app.my_project.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "tb_products")
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private Long price;
    private String category;
    private String image;
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    // ⚠️ แก้ไขจุดนี้: ชื่อต้องตรงกับ SQL ใน ProductController ของเดิม
    @Column(name = "\"stockQuantity\"") 
    private Long stockQuantity;

    // ⚠️ แก้ไขจุดนี้: ชื่อต้องตรงกับ SQL ใน ProductController ของเดิม
    @Column(name = "\"isAvailable\"")
    private Boolean isAvailable;

    // --- Constructor ---
    public ProductEntity() {}

    // (Constructor, Getters, Setters อื่นๆ คงเดิม...)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    // Getters Setters ที่สำคัญสำหรับ OrderController
    public Long getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Long stockQuantity) { this.stockQuantity = stockQuantity; }
    public Boolean getIsAvailable() { return isAvailable; }
    public void setIsAvailable(Boolean isAvailable) { this.isAvailable = isAvailable; }
}