package com.app.my_project.repository;

import com.app.my_project.entity.StoreSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreSettingRepository extends JpaRepository<StoreSettingEntity, Long> {
}