package com.app.my_project.controller;

import com.app.my_project.entity.SupplierEntity;
import com.app.my_project.models.SupplierRequest;
import com.app.my_project.service.SupplierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
@CrossOrigin(origins = "*")
public class SupplierController {

    @Autowired
    private SupplierService supplierService;

    @GetMapping
    public ResponseEntity<List<SupplierEntity>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(supplierService.getAll(search, category, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return supplierService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SupplierEntity> create(@RequestBody SupplierRequest request) {
        return ResponseEntity.ok(supplierService.create(request));
    }

    @PatchMapping("/{id}/toggle-status")
    public ResponseEntity<SupplierEntity> toggleStatus(@PathVariable Long id) {
        return ResponseEntity.ok(supplierService.toggleStatus(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SupplierEntity> update(
            @PathVariable Long id,
            @RequestBody SupplierRequest request) {
        return ResponseEntity.ok(supplierService.update(id, request));
    }
}