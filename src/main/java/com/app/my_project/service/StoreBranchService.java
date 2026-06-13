package com.app.my_project.service;

import com.app.my_project.entity.StoreBranchEntity;
import com.app.my_project.repository.StoreBranchRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** จัดการสาขาร้าน (CRUD) */
@Service
public class StoreBranchService {

    private final StoreBranchRepository repo;

    public StoreBranchService(StoreBranchRepository repo) {
        this.repo = repo;
    }

    /** สาขาที่เปิดใช้งาน (หน้าลูกค้า) */
    public List<StoreBranchEntity> listActive() {
        return repo.findByActiveTrueOrderBySortOrderAscIdAsc();
    }

    /** ทุกสาขา (หน้า admin) */
    public List<StoreBranchEntity> listAll() {
        return repo.findAllByOrderBySortOrderAscIdAsc();
    }

    public Optional<StoreBranchEntity> findById(Long id) {
        return repo.findById(id);
    }

    public StoreBranchEntity save(StoreBranchEntity b) {
        b.setUpdatedAt(LocalDateTime.now());
        return repo.save(b);
    }

    public boolean delete(Long id) {
        if (!repo.existsById(id)) return false;
        repo.deleteById(id);
        return true;
    }
}