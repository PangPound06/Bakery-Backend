package com.app.my_project.controller;

import com.app.my_project.entity.PurchaseOrderEntity;
import com.app.my_project.models.PurchaseOrderRequest;
import com.app.my_project.service.PurchaseOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/purchase-orders")

public class PurchaseOrderController {

    @Autowired
    private PurchaseOrderService poService;

    // GET /api/purchase-orders
    @GetMapping
    public ResponseEntity<List<PurchaseOrderEntity>> getAll() {
        return ResponseEntity.ok(poService.getAll());
    }

    // GET /api/purchase-orders/supplier/{supplierId}
    @GetMapping("/supplier/{supplierId}")
    public ResponseEntity<List<PurchaseOrderEntity>> getBySupplierId(
            @PathVariable Long supplierId) {
        return ResponseEntity.ok(poService.getBySupplierId(supplierId));
    }

    // POST /api/purchase-orders
    @PostMapping
    public ResponseEntity<PurchaseOrderEntity> create(@RequestBody PurchaseOrderRequest request) {
        return ResponseEntity.ok(poService.create(request));
    }

    // PATCH /api/purchase-orders/{id}/status
    // Body: { "status": "confirmed" }
    @PatchMapping("/{id}/status")
    public ResponseEntity<PurchaseOrderEntity> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(poService.updateStatus(id, body.get("status")));
    }

    // PATCH /api/purchase-orders/{id}/payment-status
    // Body: { "paymentStatus": "paid" }
    @PatchMapping("/{id}/payment-status")
    public ResponseEntity<PurchaseOrderEntity> updatePaymentStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(poService.updatePaymentStatus(id, body.get("paymentStatus")));
    }

    // PATCH /api/purchase-orders/{id}/item
    @PatchMapping("/{id}/item")
    public ResponseEntity<PurchaseOrderEntity> updateItem(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String itemName = (String) body.get("itemName");
        Integer itemQty = body.get("itemQty") != null ? ((Number) body.get("itemQty")).intValue() : null;
        String itemUnit = (String) body.get("itemUnit");
        Double itemPrice = body.get("itemPrice") != null ? ((Number) body.get("itemPrice")).doubleValue() : null;
        return ResponseEntity.ok(poService.updateItem(id, itemName, itemQty, itemUnit, itemPrice));
    }
}