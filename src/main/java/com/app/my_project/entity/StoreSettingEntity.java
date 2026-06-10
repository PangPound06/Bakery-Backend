package com.app.my_project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * ตั้งค่าร้าน (แถวเดียว id=1) — ใช้คุมการเปิด/ปิดรับออเดอร์ออนไลน์
 */
@Entity
@Table(name = "tb_store_setting")
public class StoreSettingEntity {

    @Id
    private Long id;

    @Column(name = "online_ordering", nullable = false)
    private boolean onlineOrdering = true;

    @Column(name = "closed_message", length = 255)
    private String closedMessage;

    @Column(name = "reopen_at", length = 20)
    private String reopenAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public StoreSettingEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public boolean isOnlineOrdering() {
        return onlineOrdering;
    }

    public void setOnlineOrdering(boolean onlineOrdering) {
        this.onlineOrdering = onlineOrdering;
    }

    public String getClosedMessage() {
        return closedMessage;
    }

    public void setClosedMessage(String closedMessage) {
        this.closedMessage = closedMessage;
    }

    public String getReopenAt() {
        return reopenAt;
    }

    public void setReopenAt(String reopenAt) {
        this.reopenAt = reopenAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}