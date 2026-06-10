package com.app.my_project.service;

import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.repository.StoreSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test StoreSettingService — สถานะร้านแบบแถวเดียว (id=1)
 */
@ExtendWith(MockitoExtension.class)
class StoreSettingServiceTest {

    @Mock
    private StoreSettingRepository repo;

    @InjectMocks
    private StoreSettingService service;

    private StoreSettingEntity row(boolean open) {
        StoreSettingEntity s = new StoreSettingEntity();
        s.setId(1L);
        s.setOnlineOrdering(open);
        return s;
    }

    @Test
    @DisplayName("getStatus: มีแถวอยู่แล้ว → คืนแถวนั้น ไม่ save ซ้ำ")
    void getStatusExisting() {
        StoreSettingEntity existing = row(true);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        StoreSettingEntity result = service.getStatus();

        assertThat(result).isSameAs(existing);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("getStatus: ยังไม่มีแถว → สร้าง default (id=1, เปิดรับ) แล้ว save")
    void getStatusCreatesDefault() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreSettingEntity result = service.getStatus();

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.isOnlineOrdering()).isTrue();
        verify(repo).save(any(StoreSettingEntity.class));
    }

    @Test
    @DisplayName("isOnlineOrderingOpen: สะท้อนค่าจาก getStatus")
    void isOpenReflectsStatus() {
        when(repo.findById(1L)).thenReturn(Optional.of(row(false)));

        assertThat(service.isOnlineOrderingOpen()).isFalse();
    }

    @Test
    @DisplayName("setOnlineOrdering(false): ปิดรับ + เก็บ message/reopenAt + ตั้ง updatedAt + save")
    void closeOrdering() {
        when(repo.findById(1L)).thenReturn(Optional.of(row(true)));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreSettingEntity result = service.setOnlineOrdering(false, "คิวเยอะ", "14:00");

        assertThat(result.isOnlineOrdering()).isFalse();
        assertThat(result.getClosedMessage()).isEqualTo("คิวเยอะ");
        assertThat(result.getReopenAt()).isEqualTo("14:00");
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(repo).save(result);
    }

    @Test
    @DisplayName("setOnlineOrdering(true): เปิดรับ → ล้าง message/reopenAt เป็น null")
    void openOrderingClearsMessage() {
        StoreSettingEntity existing = row(false);
        existing.setClosedMessage("คิวเยอะ");
        existing.setReopenAt("14:00");
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreSettingEntity result = service.setOnlineOrdering(true, "ignored", "ignored");

        assertThat(result.isOnlineOrdering()).isTrue();
        assertThat(result.getClosedMessage()).isNull();
        assertThat(result.getReopenAt()).isNull();
    }
}