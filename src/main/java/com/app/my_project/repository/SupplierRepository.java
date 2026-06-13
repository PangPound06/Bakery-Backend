package com.app.my_project.repository;

import com.app.my_project.entity.SupplierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierRepository extends JpaRepository<SupplierEntity, Long> {

    List<SupplierEntity> findByNameContainingIgnoreCaseOrContactNameContainingIgnoreCase(
            String name, String contactName);

    List<SupplierEntity> findByCategory(String category);

    List<SupplierEntity> findByStatus(String status);

    List<SupplierEntity> findByCategoryAndStatus(String category, String status);
}