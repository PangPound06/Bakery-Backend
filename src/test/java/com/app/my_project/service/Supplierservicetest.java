package com.app.my_project.service;

import com.app.my_project.entity.PurchaseOrderEntity;
import com.app.my_project.entity.SupplierEntity;
import com.app.my_project.models.SupplierRequest;
import com.app.my_project.repository.PurchaseOrderRepository;
import com.app.my_project.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test SupplierService (with delete() safety check)
 *
 * ใช้ @ExtendWith(MockitoExtension.class) เพื่อให้ @Mock / @InjectMocks ทำงาน
 * - Mock repository → ไม่ต้องใช้ DB จริง
 * - Test ทั้ง happy path และ edge case
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @InjectMocks
    private SupplierService supplierService;

    private SupplierEntity sampleSupplier;

    @BeforeEach
    void setUp() {
        sampleSupplier = new SupplierEntity();
        sampleSupplier.setId(1L);
        sampleSupplier.setName("ABC Bakery Supply");
        sampleSupplier.setContactName("คุณสมชาย");
        sampleSupplier.setPhone("0812345678");
        sampleSupplier.setCategory("วัตถุดิบ");
        sampleSupplier.setStatus("active");
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("สร้าง supplier ใหม่ default status = 'active'")
        void create_validRequest_savesWithActiveStatus() {
            SupplierRequest req = new SupplierRequest();
            req.setName("Test Co.");
            req.setContactName("Tester");
            req.setPhone("0800000000");
            req.setCategory("วัตถุดิบ");
            req.setPaymentTerms("Net 30 วัน");

            when(supplierRepository.save(any(SupplierEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SupplierEntity result = supplierService.create(req);

            assertThat(result.getStatus()).isEqualTo("active");
            assertThat(result.getName()).isEqualTo("Test Co.");
            verify(supplierRepository).save(any(SupplierEntity.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("toggleStatus()")
    class ToggleStatusTests {

        @Test
        @DisplayName("active → suspended")
        void toggleStatus_active_becomesSuspended() {
            sampleSupplier.setStatus("active");
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SupplierEntity result = supplierService.toggleStatus(1L);

            assertThat(result.getStatus()).isEqualTo("suspended");
        }

        @Test
        @DisplayName("suspended → active")
        void toggleStatus_suspended_becomesActive() {
            sampleSupplier.setStatus("suspended");
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SupplierEntity result = supplierService.toggleStatus(1L);

            assertThat(result.getStatus()).isEqualTo("active");
        }

        @Test
        @DisplayName("ไม่พบ id → throw exception")
        void toggleStatus_notFound_throwsException() {
            when(supplierRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> supplierService.toggleStatus(999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("delete() — safety check")
    class DeleteTests {

        @Test
        @DisplayName("ไม่มี PO ผูกอยู่ → ลบสำเร็จ")
        void delete_noPurchaseOrders_deletesSuccessfully() {
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(purchaseOrderRepository.findBySupplierId(1L)).thenReturn(List.of()); // empty

            supplierService.delete(1L);

            verify(supplierRepository).delete(sampleSupplier);
        }

        @Test
        @DisplayName("ลบ supplier ที่มี PO → ลบ PO ที่ผูกอยู่ทั้งหมดแล้วลบ supplier (ไม่ throw)")
        void delete_hasPurchaseOrders_cascadeDeletes() {
            SupplierEntity supplier = new SupplierEntity();
            supplier.setId(1L);
            List<PurchaseOrderEntity> linkedPOs = List.of(new PurchaseOrderEntity());

            when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));
            when(purchaseOrderRepository.findBySupplierId(1L)).thenReturn(linkedPOs);

            // ไม่ควร throw อีกต่อไป
            assertThatCode(() -> supplierService.delete(1L)).doesNotThrowAnyException();

            // ลบ PO ก่อน แล้วจึงลบ supplier
            verify(purchaseOrderRepository).deleteAll(linkedPOs);
            verify(supplierRepository).delete(supplier);
        }

        @Test
        @DisplayName("Supplier ไม่มีอยู่ → throw exception")
        void delete_supplierNotFound_throwsException() {
            when(supplierRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> supplierService.delete(999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not found");

            verify(supplierRepository, never()).delete(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getAll() — filter logic")
    class GetAllTests {

        @Test
        @DisplayName("ไม่มี filter → คืนทั้งหมด")
        void getAll_noFilter_returnsAll() {
            when(supplierRepository.findAll())
                    .thenReturn(List.of(sampleSupplier, new SupplierEntity()));

            List<SupplierEntity> result = supplierService.getAll(null, null, null);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("filter category → คืนเฉพาะที่ตรง")
        void getAll_filterByCategory_returnsMatching() {
            SupplierEntity packaging = new SupplierEntity();
            packaging.setCategory("บรรจุภัณฑ์");
            packaging.setStatus("active");

            when(supplierRepository.findAll()).thenReturn(List.of(sampleSupplier, packaging));

            List<SupplierEntity> result = supplierService.getAll(null, "วัตถุดิบ", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCategory()).isEqualTo("วัตถุดิบ");
        }

        @Test
        @DisplayName("filter status='ใช้งาน' → คืนเฉพาะ active (map Thai → English)")
        void getAll_filterByStatusInThai_mapsCorrectly() {
            SupplierEntity suspended = new SupplierEntity();
            suspended.setCategory("วัตถุดิบ");
            suspended.setStatus("suspended");

            when(supplierRepository.findAll()).thenReturn(List.of(sampleSupplier, suspended));

            List<SupplierEntity> result = supplierService.getAll(null, null, "ใช้งาน");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("active");
        }
    }
}