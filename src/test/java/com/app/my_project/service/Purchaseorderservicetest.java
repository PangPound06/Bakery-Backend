package com.app.my_project.service;

import com.app.my_project.entity.PurchaseOrderEntity;
import com.app.my_project.models.PurchaseOrderRequest;
import com.app.my_project.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test PurchaseOrderService
 *
 * Focus: คำนวณ total ถูกต้อง, generate poCode ไม่ซ้ำ
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock private PurchaseOrderRepository poRepository;

    @InjectMocks
    private PurchaseOrderService poService;

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("คำนวณ total = qty × price ถูกต้อง")
        void create_calculatesTotalCorrectly() {
            PurchaseOrderRequest.POItem item = new PurchaseOrderRequest.POItem();
            item.setName("แป้งสาลี");
            item.setQty(10);
            item.setUnit("กก.");
            item.setPrice(50.0);

            PurchaseOrderRequest req = new PurchaseOrderRequest();
            req.setSupplierId(1L);
            req.setSupplierName("ABC Co.");
            req.setItems(List.of(item));

            when(poRepository.count()).thenReturn(0L);
            when(poRepository.existsByPoCode(anyString())).thenReturn(false);
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.create(req);

            assertThat(result.getTotal()).isEqualTo(500.0); // 10 × 50
            assertThat(result.getStatus()).isEqualTo("pending");
            assertThat(result.getPaymentStatus()).isEqualTo("unpaid");
            assertThat(result.getItemName()).isEqualTo("แป้งสาลี");
        }

        @Test
        @DisplayName("ไม่มี items → total = 0")
        void create_emptyItems_totalIsZero() {
            PurchaseOrderRequest req = new PurchaseOrderRequest();
            req.setItems(List.of());

            when(poRepository.count()).thenReturn(0L);
            when(poRepository.existsByPoCode(anyString())).thenReturn(false);
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.create(req);

            assertThat(result.getTotal()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("qty=null → default เป็น 1 ป้องกัน NPE")
        void create_nullQty_defaultsToOne() {
            PurchaseOrderRequest.POItem item = new PurchaseOrderRequest.POItem();
            item.setName("น้ำตาล");
            item.setQty(null); // ทดสอบ null handling
            item.setPrice(20.0);

            PurchaseOrderRequest req = new PurchaseOrderRequest();
            req.setItems(List.of(item));

            when(poRepository.count()).thenReturn(0L);
            when(poRepository.existsByPoCode(anyString())).thenReturn(false);
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.create(req);

            assertThat(result.getItemQty()).isEqualTo(1);
        }

        @Test
        @DisplayName("PO Code มี format 'PO-YYYY-NNN'")
        void create_poCodeHasCorrectFormat() {
            PurchaseOrderRequest req = new PurchaseOrderRequest();
            req.setItems(List.of());

            when(poRepository.count()).thenReturn(0L);
            when(poRepository.existsByPoCode(anyString())).thenReturn(false);
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.create(req);

            String currentYear = String.valueOf(LocalDate.now().getYear());
            assertThat(result.getPoCode())
                    .startsWith("PO-" + currentYear + "-")
                    .matches("PO-\\d{4}-\\d{3,}");
        }

        @Test
        @DisplayName("ถ้า poCode ซ้ำ → เพิ่ม counter จนกว่าจะไม่ซ้ำ")
        void create_duplicatePoCode_retriesUntilUnique() {
            PurchaseOrderRequest req = new PurchaseOrderRequest();
            req.setItems(List.of());

            when(poRepository.count()).thenReturn(0L);
            // ครั้งแรก code ซ้ำ ครั้งที่สองไม่ซ้ำ
            when(poRepository.existsByPoCode(anyString()))
                    .thenReturn(true)   // PO-2026-001 ซ้ำ
                    .thenReturn(false); // PO-2026-002 ใช้ได้
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.create(req);

            assertThat(result.getPoCode()).endsWith("-002");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("updateStatus() & updatePaymentStatus()")
    class UpdateTests {

        @Test
        @DisplayName("เปลี่ยน status สำเร็จ")
        void updateStatus_validId_updates() {
            PurchaseOrderEntity entity = new PurchaseOrderEntity();
            entity.setId(1L);
            entity.setStatus("pending");

            when(poRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.updateStatus(1L, "confirmed");

            assertThat(result.getStatus()).isEqualTo("confirmed");
        }

        @Test
        @DisplayName("ไม่พบ id → throw exception")
        void updateStatus_notFound_throws() {
            when(poRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> poService.updateStatus(999L, "confirmed"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("updateItem() — recalculation")
    class UpdateItemTests {

        @Test
        @DisplayName("แก้ price → คำนวณ total ใหม่ตาม qty เดิม")
        void updateItem_changePrice_recalculatesTotal() {
            PurchaseOrderEntity entity = new PurchaseOrderEntity();
            entity.setId(1L);
            entity.setItemQty(5);
            entity.setItemPrice(100.0);
            entity.setTotal(500.0);

            when(poRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.updateItem(1L, null, null, null, 200.0);

            assertThat(result.getItemPrice()).isEqualTo(200.0);
            assertThat(result.getTotal()).isEqualTo(1000.0); // 5 × 200
        }

        @Test
        @DisplayName("ส่ง null ทุก field → ไม่เปลี่ยนค่า")
        void updateItem_allNull_keepsExistingValues() {
            PurchaseOrderEntity entity = new PurchaseOrderEntity();
            entity.setItemName("แป้ง");
            entity.setItemQty(5);
            entity.setItemPrice(100.0);

            when(poRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(poRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PurchaseOrderEntity result = poService.updateItem(1L, null, null, null, null);

            assertThat(result.getItemName()).isEqualTo("แป้ง");
            assertThat(result.getItemQty()).isEqualTo(5);
            assertThat(result.getItemPrice()).isEqualTo(100.0);
        }
    }
}