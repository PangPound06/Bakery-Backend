package com.app.my_project.controller;

import com.app.my_project.entity.PurchaseOrderEntity;
import com.app.my_project.models.PurchaseOrderRequest;
import com.app.my_project.service.PurchaseOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test PurchaseOrderController
 *
 * Controller บางมาก ส่งต่อไปยัง service ทั้งหมด
 * Focus: ตรวจการแปลง Map → typed args ใน updateItem
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderControllerTest {

    @Mock private PurchaseOrderService poService;

    @InjectMocks
    private PurchaseOrderController controller;

    @Test
    @DisplayName("GET /api/purchase-orders → คืน list จาก service")
    void getAll_returnsList() {
        when(poService.getAll()).thenReturn(List.of(new PurchaseOrderEntity()));

        ResponseEntity<?> response = controller.getAll();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /api/purchase-orders/supplier/{id} → filter by supplier")
    void getBySupplierId_passesIdToService() {
        when(poService.getBySupplierId(5L)).thenReturn(List.of());

        controller.getBySupplierId(5L);

        verify(poService).getBySupplierId(5L);
    }

    @Test
    @DisplayName("POST → delegate ไปสร้าง")
    void create_delegates() {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        when(poService.create(any())).thenReturn(new PurchaseOrderEntity());

        ResponseEntity<PurchaseOrderEntity> response = controller.create(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(poService).create(req);
    }

    @Test
    @DisplayName("PATCH /status → ส่ง status จาก body ไปยัง service")
    void updateStatus_extractsStatusFromBody() {
        when(poService.updateStatus(eq(1L), eq("confirmed")))
                .thenReturn(new PurchaseOrderEntity());

        ResponseEntity<?> response = controller.updateStatus(1L, Map.of("status", "confirmed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(poService).updateStatus(1L, "confirmed");
    }

    @Test
    @DisplayName("PATCH /payment-status → ส่ง paymentStatus จาก body")
    void updatePaymentStatus_extractsFromBody() {
        when(poService.updatePaymentStatus(eq(1L), eq("paid")))
                .thenReturn(new PurchaseOrderEntity());

        controller.updatePaymentStatus(1L, Map.of("paymentStatus", "paid"));

        verify(poService).updatePaymentStatus(1L, "paid");
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("PATCH /item → แปลง Map<String,Object> เป็น typed args ถูกต้อง")
    void updateItem_convertsTypesCorrectly() {
        Map<String, Object> body = new HashMap<>();
        body.put("itemName", "น้ำตาล");
        body.put("itemQty", 10);          // Integer
        body.put("itemUnit", "กก.");
        body.put("itemPrice", 25.50);     // Double

        when(poService.updateItem(eq(1L), eq("น้ำตาล"), eq(10), eq("กก."), eq(25.50)))
                .thenReturn(new PurchaseOrderEntity());

        controller.updateItem(1L, body);

        verify(poService).updateItem(1L, "น้ำตาล", 10, "กก.", 25.50);
    }

    @Test
    @DisplayName("PATCH /item → field ที่ null ส่ง null ไป (partial update)")
    void updateItem_nullFields_passNull() {
        Map<String, Object> body = new HashMap<>();
        body.put("itemName", "น้ำตาล");
        // ไม่ใส่ itemQty / itemPrice

        when(poService.updateItem(any(), any(), any(), any(), any()))
                .thenReturn(new PurchaseOrderEntity());

        controller.updateItem(1L, body);

        verify(poService).updateItem(1L, "น้ำตาล", null, null, null);
    }

    @Test
    @DisplayName("PATCH /item → itemQty ส่งมาเป็น Long ก็แปลง Integer ได้ (Number cast)")
    void updateItem_qtyAsLong_castsCorrectly() {
        Map<String, Object> body = new HashMap<>();
        body.put("itemQty", 5L); // Long แทน Integer

        when(poService.updateItem(any(), any(), any(), any(), any()))
                .thenReturn(new PurchaseOrderEntity());

        controller.updateItem(1L, body);

        // Should be cast to Integer 5
        verify(poService).updateItem(1L, null, 5, null, null);
    }
}