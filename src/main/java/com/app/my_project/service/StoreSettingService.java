package com.app.my_project.service;

import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.repository.StoreSettingRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * อ่าน/อัปเดตสถานะร้าน — ใช้แถวเดียว (id=1) ถ้ายังไม่มีจะสร้างให้อัตโนมัติ (เปิดรับเป็นค่าเริ่มต้น)
 */
@Service
public class StoreSettingService {

    private static final Long SINGLETON_ID = 1L;

    private final StoreSettingRepository repo;

    public StoreSettingService(StoreSettingRepository repo) {
        this.repo = repo;
    }

    /** ดึงสถานะปัจจุบัน (สร้าง default ถ้ายังไม่มี) */
    public StoreSettingEntity getStatus() {
        return repo.findById(SINGLETON_ID).orElseGet(() -> {
            StoreSettingEntity s = new StoreSettingEntity();
            s.setId(SINGLETON_ID);
            s.setOnlineOrdering(true);
            s.setUpdatedAt(LocalDateTime.now());
            return repo.save(s);
        });
    }

    /** เปิดรับออเดอร์ออนไลน์อยู่ไหม */
    public boolean isOnlineOrderingOpen() {
        return getStatus().isOnlineOrdering();
    }

    /** เปิด/ปิดรับออเดอร์ออนไลน์ (ตอนเปิดจะล้าง message/reopenAt ทิ้ง) */
    public StoreSettingEntity setOnlineOrdering(boolean open, String message, String reopenAt) {
        StoreSettingEntity s = getStatus();
        s.setOnlineOrdering(open);
        s.setClosedMessage(open ? null : message);
        s.setReopenAt(open ? null : reopenAt);
        s.setUpdatedAt(LocalDateTime.now());
        return repo.save(s);
    }
}