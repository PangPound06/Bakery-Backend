package com.app.my_project.repository;

import com.app.my_project.entity.SupplierEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupplierRepository extends JpaRepository<SupplierEntity, Long> {

    List<SupplierEntity> findByNameContainingIgnoreCaseOrContactNameContainingIgnoreCase(
            String name, String contactName);

    List<SupplierEntity> findByCategory(String category);

    List<SupplierEntity> findByStatus(String status);

    List<SupplierEntity> findByCategoryAndStatus(String category, String status);
}