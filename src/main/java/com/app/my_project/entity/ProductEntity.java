package com.app.my_project.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ProductEntity — final fix
 *
 * ปัญหา: Spring Boot 3.x ใช้ CamelCaseToUnderscoresNamingStrategy
 * → แม้ใส่ @Column(name="\"stockQuantity\"") Hibernate ยังแปลงเป็น
 * "stock_quantity"
 *
 * วิธีแก้: ใช้ backtick `` `` (grave accent) ครอบชื่อ column
 * → JPA spec บอก backtick = "delimited identifier" → Hibernate ห้ามแปลง
 * → ตรง keyword `Quoted identifier` ของ Hibernate
 * → DB จะได้ "stockQuantity" ตามจริง
 */
@Entity
@Table(name = "tb_products")
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private Double price;

    private String category;

    @Column(name = "category_id")
    private Long categoryId;

    private String image;
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    // ✅ backtick = delimited identifier → Hibernate ไม่แปลง naming strategy
    @Column(name = "`stockQuantity`")
    @JsonProperty("stockQuantity")
    private Long stockQuantity;

    @Column(name = "`isAvailable`")
    @JsonProperty("isAvailable")
    private Boolean isAvailable;

    @Column(columnDefinition = "TEXT")
    private String options;

    public ProductEntity() {
    }

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

    @JsonProperty("isAvailable")
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