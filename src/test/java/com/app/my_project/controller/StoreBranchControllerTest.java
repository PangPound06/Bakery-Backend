package com.app.my_project.controller;

import com.app.my_project.entity.StoreBranchEntity;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.StoreBranchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test StoreBranchController — public GET + admin CRUD (gate ด้วย isAdmin)
 */
@ExtendWith(MockitoExtension.class)
class StoreBranchControllerTest {

    @Mock
    private StoreBranchService service;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private StoreBranchController controller;

    private static final String AUTH = "Bearer t";

    private StoreBranchEntity branch(Long id, String name) {
        StoreBranchEntity b = new StoreBranchEntity();
        b.setId(id);
        b.setName(name);
        b.setLatitude(13.7);
        b.setLongitude(100.5);
        return b;
    }

    @Test
    @DisplayName("GET /: public คืนสาขาที่เปิดใช้งาน → 200")
    void listActive() {
        when(service.listActive())
                .thenReturn(List.of(branch(1L, "A"), branch(2L, "B")));

        ResponseEntity<Map<String, Object>> res = controller.listActive();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .containsEntry("success", true)
                .containsKey("branches");
    }

    @Test
    @DisplayName("GET /all: ไม่ใช่ admin → 403 และไม่เรียก service")
    void listAllForbidden() {
        when(jwtService.isAdmin(AUTH)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.listAll(AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).listAll();
    }

    @Test
    @DisplayName("GET /all: admin → 200")
    void listAllOk() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(service.listAll()).thenReturn(List.of(branch(1L, "A")));

        ResponseEntity<Map<String, Object>> res = controller.listAll(AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("branches");
    }

    @Test
    @DisplayName("POST: ไม่ใช่ admin → 403 และไม่เรียก save")
    void createForbidden() {
        when(jwtService.isAdmin(AUTH)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.create(
                Map.of("name", "A", "latitude", 13.7, "longitude", 100.5), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("POST: ไม่กรอกชื่อ → 400")
    void createMissingName() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.create(
                Map.of("latitude", 13.7, "longitude", 100.5), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("POST: ไม่กรอกพิกัด → 400")
    void createMissingCoords() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res =
                controller.create(Map.of("name", "สาขา A"), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("POST: ข้อมูลครบ → 200 + เรียก save")
    void createOk() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(service.save(any(StoreBranchEntity.class)))
                .thenReturn(branch(10L, "สาขา A"));

        ResponseEntity<Map<String, Object>> res = controller.create(
                Map.of("name", "สาขา A", "latitude", 13.7, "longitude", 100.5),
                AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .containsEntry("success", true)
                .containsKey("branch");
        verify(service).save(any(StoreBranchEntity.class));
    }

    @Test
    @DisplayName("PUT: ไม่พบสาขา → 404 และไม่เรียก save")
    void updateNotFound() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(service.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> res =
                controller.update(99L, Map.of("name", "X"), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("PUT: พบสาขา + ข้อมูลครบ → 200")
    void updateOk() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(service.findById(5L)).thenReturn(Optional.of(branch(5L, "เดิม")));
        when(service.save(any(StoreBranchEntity.class)))
                .thenReturn(branch(5L, "ใหม่"));

        ResponseEntity<Map<String, Object>> res =
                controller.update(5L, Map.of("name", "ใหม่"), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("branch");
    }

    @Test
    @DisplayName("DELETE: ไม่ใช่ admin → 403")
    void deleteForbidden() {
        when(jwtService.isAdmin(AUTH)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.delete(1L, AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).delete(anyLong());
    }

    @Test
    @DisplayName("DELETE: ไม่พบสาขา → 404")
    void deleteNotFound() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(service.delete(7L)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.delete(7L, AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE: สำเร็จ → 200")
    void deleteOk() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(service.delete(3L)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.delete(3L, AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
    }
}