package com.app.my_project.service;

import com.app.my_project.entity.StoreBranchEntity;
import com.app.my_project.repository.StoreBranchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test StoreBranchService — CRUD สาขาร้าน
 */
@ExtendWith(MockitoExtension.class)
class StoreBranchServiceTest {

    @Mock
    private StoreBranchRepository repo;

    @InjectMocks
    private StoreBranchService service;

    private StoreBranchEntity branch(Long id, String name) {
        StoreBranchEntity b = new StoreBranchEntity();
        b.setId(id);
        b.setName(name);
        b.setLatitude(13.7);
        b.setLongitude(100.5);
        return b;
    }

    @Test
    @DisplayName("listActive: ดึงเฉพาะสาขาที่เปิดใช้งาน (delegate ไป repo)")
    void listActive() {
        List<StoreBranchEntity> rows = List.of(branch(1L, "A"), branch(2L, "B"));
        when(repo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(rows);

        assertThat(service.listActive()).hasSize(2).isEqualTo(rows);
    }

    @Test
    @DisplayName("listAll: ดึงทุกสาขา")
    void listAll() {
        when(repo.findAllByOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(branch(1L, "A")));

        assertThat(service.listAll()).hasSize(1);
    }

    @Test
    @DisplayName("findById: delegate ไป repo")
    void findById() {
        StoreBranchEntity b = branch(5L, "X");
        when(repo.findById(5L)).thenReturn(Optional.of(b));

        assertThat(service.findById(5L)).contains(b);
    }

    @Test
    @DisplayName("save: ตั้ง updatedAt แล้ว save")
    void save() {
        StoreBranchEntity b = branch(null, "ใหม่");
        when(repo.save(b)).thenReturn(b);

        StoreBranchEntity saved = service.save(b);

        assertThat(saved.getUpdatedAt()).isNotNull();
        verify(repo).save(b);
    }

    @Test
    @DisplayName("delete: มีอยู่ → ลบ + คืน true")
    void deleteExisting() {
        when(repo.existsById(3L)).thenReturn(true);

        assertThat(service.delete(3L)).isTrue();
        verify(repo).deleteById(3L);
    }

    @Test
    @DisplayName("delete: ไม่มี → คืน false และไม่เรียก deleteById")
    void deleteMissing() {
        when(repo.existsById(9L)).thenReturn(false);

        assertThat(service.delete(9L)).isFalse();
        verify(repo, never()).deleteById(anyLong());
    }
}