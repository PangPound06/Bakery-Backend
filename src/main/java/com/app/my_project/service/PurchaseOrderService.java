package com.app.my_project.service;

import com.app.my_project.entity.PurchaseOrderEntity;
import com.app.my_project.models.PurchaseOrderRequest;
import com.app.my_project.repository.PurchaseOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class PurchaseOrderService {

    @Autowired
    private PurchaseOrderRepository poRepository;

    public List<PurchaseOrderEntity> getAll() {
        return poRepository.findAll();
    }

    public List<PurchaseOrderEntity> getBySupplierId(Long supplierId) {
        return poRepository.findBySupplierId(supplierId);
    }

    public PurchaseOrderEntity create(PurchaseOrderRequest req) {
        PurchaseOrderEntity entity = new PurchaseOrderEntity();
        entity.setSupplierId(req.getSupplierId());
        entity.setSupplierName(req.getSupplierName());
        entity.setPoCode(generatePoCode());
        entity.setNote(req.getNote());
        entity.setStatus("pending");
        entity.setPaymentStatus("unpaid");

        // เอาแค่ item แรก
        if (req.getItems() != null && !req.getItems().isEmpty()) {
            PurchaseOrderRequest.POItem item = req.getItems().get(0);
            entity.setItemName(item.getName());
            entity.setItemQty(item.getQty() != null ? item.getQty() : 1);
            entity.setItemUnit(item.getUnit());
            entity.setItemPrice(item.getPrice() != null ? item.getPrice() : 0.0);
            entity.setTotal((item.getQty() != null ? item.getQty() : 0)
                    * (item.getPrice() != null ? item.getPrice() : 0));
        } else {
            entity.setTotal(0.0);
        }

        return poRepository.save(entity);
    }

    public PurchaseOrderEntity updateStatus(Long id, String status) {
        PurchaseOrderEntity entity = poRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("PO not found: " + id));
        entity.setStatus(status);
        return poRepository.save(entity);
    }

    public PurchaseOrderEntity updatePaymentStatus(Long id, String paymentStatus) {
        PurchaseOrderEntity entity = poRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("PO not found: " + id));
        entity.setPaymentStatus(paymentStatus);
        return poRepository.save(entity);
    }

    private String generatePoCode() {
        String year = String.valueOf(LocalDate.now().getYear());
        long count = poRepository.count() + 1;
        String seq = String.format("%03d", count);
        String code = "PO-" + year + "-" + seq;
        while (poRepository.existsByPoCode(code)) {
            count++;
            seq = String.format("%03d", count);
            code = "PO-" + year + "-" + seq;
        }
        return code;
    }

    // ── อัปเดตข้อมูลสินค้าใน PO ──
    public PurchaseOrderEntity updateItem(Long id, String itemName, Integer itemQty, String itemUnit,
            Double itemPrice) {
        PurchaseOrderEntity entity = poRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("PO not found: " + id));
        if (itemName != null)
            entity.setItemName(itemName);
        if (itemQty != null)
            entity.setItemQty(itemQty);
        if (itemUnit != null)
            entity.setItemUnit(itemUnit);
        if (itemPrice != null) {
            entity.setItemPrice(itemPrice);
            entity.setTotal((entity.getItemQty() != null ? entity.getItemQty() : 1) * itemPrice);
        }
        return poRepository.save(entity);
    }
}