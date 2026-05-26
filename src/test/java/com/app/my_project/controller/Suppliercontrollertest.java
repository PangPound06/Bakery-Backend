package com.app.my_project.controller;

import com.app.my_project.entity.SupplierEntity;
import com.app.my_project.service.SupplierService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test SupplierController
 *
 * เนื่องจาก controller เป็น thin layer (ส่งต่อไปยัง service ทั้งหมด)
 * เน้น test แค่ HTTP behavior:
 *   - status code ถูกไหม
 *   - error handling (โดยเฉพาะ delete() ที่ catch exception)
 */
@ExtendWith(MockitoExtension.class)
class SupplierControllerTest {

    @Mock private SupplierService supplierService;

    @InjectMocks
    private SupplierController controller;

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/suppliers/{id} — safety check + error handling")
    class DeleteTests {

        @Test
        @DisplayName("ลบสำเร็จ → 200 + success=true")
        void delete_success_returns200() {
            doNothing().when(supplierService).delete(1L);

            ResponseEntity<?> response = controller.delete(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("success", true);
            assertThat((String) body.get("message")).contains("สำเร็จ");
        }

        @Test
        @DisplayName("Service throw exception (มี PO) → 400 + success=false + message")
        void delete_serviceThrows_returns400WithMessage() {
            doThrow(new RuntimeException("ไม่สามารถลบได้ เนื่องจากมีคำสั่งซื้อ 3 รายการผูกอยู่"))
                    .when(supplierService).delete(1L);

            ResponseEntity<?> response = controller.delete(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("success", false);
            assertThat((String) body.get("message")).contains("3 รายการ");
        }

        @Test
        @DisplayName("Service ตอบ 'not found' → 400")
        void delete_notFound_returns400() {
            doThrow(new RuntimeException("Supplier not found: 999"))
                    .when(supplierService).delete(999L);

            ResponseEntity<?> response = controller.delete(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/suppliers/{id}")
    class GetByIdTests {

        @Test
        @DisplayName("พบ → 200 + entity")
        void getById_found_returns200() {
            SupplierEntity entity = new SupplierEntity();
            entity.setId(1L);
            when(supplierService.getById(1L)).thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.getById(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ไม่พบ → 404")
        void getById_notFound_returns404() {
            when(supplierService.getById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getById(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("PATCH /api/suppliers/{id}/toggle-status — delegates to service")
    void toggleStatus_delegatesToService() {
        SupplierEntity entity = new SupplierEntity();
        entity.setStatus("suspended");
        when(supplierService.toggleStatus(1L)).thenReturn(entity);

        ResponseEntity<SupplierEntity> response = controller.toggleStatus(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo("suspended");
        verify(supplierService).toggleStatus(1L);
    }
}