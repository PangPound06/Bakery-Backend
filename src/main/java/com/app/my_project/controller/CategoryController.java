package com.app.my_project.controller;

import com.app.my_project.entity.CategoryEntity;
import com.app.my_project.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.app.my_project.repository.ProductRepository;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/categories")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class CategoryController {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @GetMapping
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(categoryRepository.findAllByOrderByDisplayOrderAsc());
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActive() {
        return ResponseEntity.ok(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = (String) request.get("name");
            String icon = request.get("icon") != null ? (String) request.get("icon") : "";

            if (name == null || name.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "กรุณากรอกชื่อหมวดหมู่");
                return ResponseEntity.badRequest().body(response);
            }

            String slug = name.trim().toLowerCase().replaceAll("[^a-z0-9]", "-");

            if (categoryRepository.existsBySlug(slug)) {
                response.put("success", false);
                response.put("message", "หมวดหมู่นี้มีอยู่แล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            CategoryEntity cat = new CategoryEntity();
            cat.setName(name.trim());
            cat.setSlug(slug);
            cat.setIcon(icon);
            cat.setIsActive(true);
            cat.setCreatedAt(LocalDateTime.now());

            Integer maxOrder = categoryRepository.findAllByOrderByDisplayOrderAsc()
                    .stream().map(CategoryEntity::getDisplayOrder)
                    .filter(Objects::nonNull).max(Integer::compare).orElse(0);
            cat.setDisplayOrder(maxOrder + 1);

            CategoryEntity saved = categoryRepository.save(cat);
            response.put("success", true);
            response.put("category", saved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        Optional<CategoryEntity> opt = categoryRepository.findById(id);
        if (opt.isEmpty()) {
            response.put("success", false);
            response.put("message", "ไม่พบหมวดหมู่");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            CategoryEntity cat = opt.get();
            String oldSlug = cat.getSlug(); // ✅ เก็บ slug เก่า

            if (request.containsKey("name")) {
                String name = (String) request.get("name");
                String newSlug = name.trim().toLowerCase().replaceAll("[^a-z0-9]", "-");
                cat.setName(name.trim());
                cat.setSlug(newSlug);

                // ✅ อัปเดต category ของสินค้าทั้งหมดที่ใช้ slug เก่า
                if (!oldSlug.equals(newSlug)) {
                    productRepository.updateCategorySlug(oldSlug, newSlug);
                }
            }
            if (request.containsKey("icon"))
                cat.setIcon((String) request.get("icon"));
            if (request.containsKey("isActive"))
                cat.setIsActive((Boolean) request.get("isActive"));
            if (request.containsKey("displayOrder"))
                cat.setDisplayOrder((Integer) request.get("displayOrder"));

            CategoryEntity saved = categoryRepository.save(cat);
            response.put("success", true);
            response.put("category", saved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "เกิดข้อผิดพลาด");
            return ResponseEntity.badRequest().body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        if (!categoryRepository.existsById(id)) {
            response.put("success", false);
            response.put("message", "ไม่พบหมวดหมู่");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            categoryRepository.deleteById(id);
            response.put("success", true);
            response.put("message", "ลบหมวดหมู่สำเร็จ");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "ไม่สามารถลบได้ เนื่องจากมีสินค้าในหมวดหมู่นี้");
            return ResponseEntity.badRequest().body(response);
        }
    }
}