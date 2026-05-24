package com.app.my_project.service;

import com.app.my_project.entity.SupplierEntity;
import com.app.my_project.models.SupplierRequest;
import com.app.my_project.repository.SupplierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.app.my_project.repository.PurchaseOrderRepository;

import java.util.List;
import java.util.Optional;

@Service
public class SupplierService {

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    public List<SupplierEntity> getAll(String search, String category, String status) {
        List<SupplierEntity> all;

        if (search != null && !search.isBlank()) {
            all = supplierRepository
                    .findByNameContainingIgnoreCaseOrContactNameContainingIgnoreCase(search, search);
        } else {
            all = supplierRepository.findAll();
        }

        if (category != null && !category.isBlank() && !category.equals("ทั้งหมด")) {
            all = all.stream().filter(s -> category.equals(s.getCategory())).toList();
        }

        if (status != null && !status.isBlank() && !status.equals("ทั้งหมด")) {
            String mapped = status.equals("ใช้งาน") ? "active" : "suspended";
            all = all.stream().filter(s -> mapped.equals(s.getStatus())).toList();
        }

        return all;
    }

    public Optional<SupplierEntity> getById(Long id) {
        return supplierRepository.findById(id);
    }

    public SupplierEntity create(SupplierRequest req) {
        SupplierEntity entity = new SupplierEntity();
        entity.setName(req.getName());
        entity.setContactName(req.getContactName());
        entity.setPhone(req.getPhone());
        entity.setEmail(req.getEmail());
        entity.setTaxId(req.getTaxId());
        entity.setAddress(req.getAddress());
        entity.setCategory(req.getCategory());
        entity.setPaymentTerms(req.getPaymentTerms());
        entity.setNote(req.getNote());
        entity.setStatus("active");
        return supplierRepository.save(entity);
    }

    public SupplierEntity toggleStatus(Long id) {
        SupplierEntity entity = supplierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Supplier not found: " + id));
        entity.setStatus("active".equals(entity.getStatus()) ? "suspended" : "active");
        return supplierRepository.save(entity);
    }

    public SupplierEntity update(Long id, SupplierRequest req) {
        SupplierEntity entity = supplierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Supplier not found: " + id));
        entity.setName(req.getName());
        entity.setContactName(req.getContactName());
        entity.setPhone(req.getPhone());
        entity.setEmail(req.getEmail());
        entity.setTaxId(req.getTaxId());
        entity.setAddress(req.getAddress());
        entity.setCategory(req.getCategory());
        entity.setPaymentTerms(req.getPaymentTerms());
        entity.setNote(req.getNote());
        return supplierRepository.save(entity);
    }

    public void delete(Long id) {
        SupplierEntity entity = supplierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Supplier not found: " + id));

        // safety check: เช็คว่ามี PO ผูกอยู่ไหม
        int poCount = purchaseOrderRepository.findBySupplierId(id).size();
        if (poCount > 0) {
            throw new RuntimeException(
                    "ไม่สามารถลบได้ เนื่องจากมีคำสั่งซื้อ " + poCount + " รายการผูกอยู่ " +
                            "กรุณาใช้ปุ่ม 'ระงับ' แทน หรือลบ PO ทั้งหมดก่อน");
        }

        supplierRepository.delete(entity);
    }
}