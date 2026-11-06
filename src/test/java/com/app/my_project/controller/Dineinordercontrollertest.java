package com.app.my_project.controller;

import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.common.CheckoutMethod;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.service.DineInBillService;
import com.app.my_project.service.PaymentStarter;
import com.app.my_project.service.PaymentStateService;
import com.app.my_project.service.PricingService;
import com.app.my_project.service.StripeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test DineInOrderController
 *
 * Focus:
 *  - createOrder / request-bill: ส่งต่อให้ DineInBillService (ตรรกะทดสอบใน DineInBillServiceTest)
 *  - getMyOrders: แปลงออเดอร์ active เป็น JSON
 *  - updateStatus: validate ว่า status ที่ส่งมาอยู่ใน VALID_STATUSES
 *  - cancelOrder: ห้ามยกเลิก completed/paid
 *  - getOrderDetail: 404 ถ้าไม่พบ
 *
 * Note: ไม่ test stock restore ใน cancelOrder เพราะใช้ raw SQL ผ่าน DataSource
 *       แต่ test validation rules ทั้งหมดก่อนถึงส่วน SQL
 */
@ExtendWith(MockitoExtension.class)
class DineInOrderControllerTest {

    @Mock private DineInOrderRepository dineInOrderRepository;
    @Mock private DineInOrderItemRepository dineInOrderItemRepository;
    @Mock private DataSource dataSource;
    @Mock private DineInBillService billService;
    @Mock private PaymentStarter paymentStarter;
    @Mock private PaymentStateService paymentStateService;
    @Mock private StripeService stripeService;

    @InjectMocks
    private DineInOrderController controller;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void mockUser(String email) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private DineInOrderEntity makeOrder(Long id, String email, String tableNo,
                                         String orderStatus, String paymentStatus) {
        DineInOrderEntity o = new DineInOrderEntity();
        o.setId(id);
        o.setEmail(email);
        o.setTableNo(tableNo);
        o.setOrderStatus(orderStatus);
        o.setPaymentStatus(paymentStatus);
        o.setSubtotal(100.0);
        o.setTotal(100.0);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/dinein/orders — ส่งตะกร้าทานในร้านเข้าครัว")
    class CreateOrderTests {

        @Test
        @DisplayName("✅ ใช้ email จาก token และส่งแค่เลขโต๊ะให้ service (ไม่รับราคาจาก body)")
        void createOrder_usesTokenEmailAndTableOnly() {
            mockUser("user@example.com");
            DineInOrderEntity saved = makeOrder(1L, "user@example.com", "5", "pending", null);
            saved.setOrdCode("ORD1");
            when(billService.placeTableOrder("user@example.com", "5", DineInBillService.DineType.ALACARTE)).thenReturn(saved);

            ResponseEntity<?> response = controller.createOrder(Map.of("tableNo", "5", "total", 1));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).asString().contains("ORD1");
        }

        @Test
        @DisplayName("ตะกร้าว่าง → 400 พร้อมข้อความ")
        void createOrder_emptyCart_returns400() {
            mockUser("user@example.com");
            when(billService.placeTableOrder("user@example.com", "5", DineInBillService.DineType.ALACARTE))
                    .thenThrow(new PricingService.CartValidationException("ตะกร้าสินค้าว่าง"));

            ResponseEntity<?> response = controller.createOrder(Map.of("tableNo", "5"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).asString().contains("ตะกร้าสินค้าว่าง");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/dinein/request-bill")
    class RequestBillTests {

        @Test
        @DisplayName("วิธีจ่ายที่ไม่รู้จัก → 400 ไม่สร้างบิล")
        void unknownMethod_400() {
            mockUser("user@example.com");

            ResponseEntity<?> response = controller.requestBill(Map.of("paymentMethod", "bitcoin"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(billService);
        }

        @Test
        @DisplayName("✅ บัตร → เตรียมบิลแล้วส่ง clientSecret กลับ")
        void card_startsStripeCard() throws Exception {
            mockUser("user@example.com");
            when(stripeService.isEnabled()).thenReturn(true);
            OrderEntity bill = new OrderEntity();
            bill.setId(9L);
            bill.setOrdCode("ORD9");
            bill.setTotal(250.0);
            when(billService.prepareBill(eq("user@example.com"), eq("5"),
                    eq(DineInBillService.DineType.ALACARTE), eq(1), eq(CheckoutMethod.CARD))).thenReturn(bill);
            when(paymentStarter.start(bill, 250.0, "user@example.com", CheckoutMethod.CARD))
                    .thenReturn(Map.of("clientSecret", "cs_test"));

            ResponseEntity<?> response = controller.requestBill(
                    Map.of("tableNo", "5", "dineType", "alacarte", "paymentMethod", "card"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).asString().contains("cs_test").contains("ORD9");
        }

        @Test
        @DisplayName("ออกช่องทางจ่ายเงินไม่สำเร็จ → ลบบิลทิ้ง ไม่ค้าง")
        void startFails_discardsBill() throws Exception {
            mockUser("user@example.com");
            OrderEntity bill = new OrderEntity();
            bill.setId(9L);
            bill.setTotal(250.0);
            when(billService.prepareBill(any(), any(), any(), anyInt(), eq(CheckoutMethod.TRANSFER))).thenReturn(bill);
            when(paymentStarter.start(any(), anyDouble(), any(), any())).thenThrow(new IllegalStateException("qr"));

            ResponseEntity<?> response = controller.requestBill(Map.of("tableNo", "5", "paymentMethod", "transfer"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(paymentStateService).discardUnpaid(eq(9L), any());
        }

        @Test
        @DisplayName("แจ้งโอนค้างอยู่ → 409 ตาม service")
        void conflict_passesStatusThrough() {
            mockUser("user@example.com");
            when(billService.prepareBill(any(), any(), any(), anyInt(), any()))
                    .thenThrow(new DineInBillService.BillException(HttpStatus.CONFLICT, "แจ้งโอนแล้ว"));

            ResponseEntity<?> response = controller.requestBill(Map.of("paymentMethod", "transfer"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/dinein/session — เวลาบุฟเฟต์ที่เหลือ")
    class SessionTests {

        @Test
        @DisplayName("บุฟเฟต์หมดเวลาแล้ว → canOrder=false, secondsLeft=0")
        void buffetExpired() {
            mockUser("user@example.com");
            LocalDateTime now = LocalDateTime.of(2026, 11, 6, 20, 0);
            when(billService.now()).thenReturn(now);
            when(billService.currentSession("user@example.com", "5")).thenReturn(Optional.of(
                    new DineInBillService.DiningSession(DineInBillService.DineType.BUFFET, now.minusMinutes(101))));

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) controller.session("5").getBody();

            assertThat(body).containsEntry("active", true).containsEntry("dineType", "buffet")
                    .containsEntry("canOrder", false).containsEntry("secondsLeft", 0L);
        }

        @Test
        @DisplayName("ยังไม่ได้สั่ง → active=false สั่งได้")
        void noSession() {
            mockUser("user@example.com");
            when(billService.currentSession("user@example.com", null)).thenReturn(Optional.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) controller.session(null).getBody();

            assertThat(body).containsEntry("active", false).containsEntry("canOrder", true);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/dinein/my-orders")
    class GetMyOrdersTests {

        @Test
        @DisplayName("✅ คืนออเดอร์ active จาก service พร้อมรายการ")
        void getMyOrders_mapsActiveOrders() {
            mockUser("user@example.com");
            DineInOrderEntity active = makeOrder(1L, "user@example.com", "5", "pending", null);
            when(billService.activeTableOrders("user@example.com", "5")).thenReturn(List.of(active));
            when(dineInOrderItemRepository.findByOrderId(1L)).thenReturn(List.of());

            ResponseEntity<?> response = controller.getMyOrders("5");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody();
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsEntry("id", 1L).containsEntry("tableNo", "5");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/dinein/admin/orders/{id}/status — validate VALID_STATUSES")
    class UpdateStatusTests {

        @Test
        @DisplayName("✅ status='preparing' (valid) → success")
        void updateStatus_validStatus_succeeds() {
            DineInOrderEntity order = makeOrder(1L, "user@example.com", "5", "pending", "unpaid");
            when(dineInOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(dineInOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResponseEntity<?> response = controller.updateStatus(
                    1L, Map.of("orderStatus", "preparing"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(castBody(response)).containsEntry("success", true);

            ArgumentCaptor<DineInOrderEntity> captor =
                    ArgumentCaptor.forClass(DineInOrderEntity.class);
            verify(dineInOrderRepository).save(captor.capture());
            assertThat(captor.getValue().getOrderStatus()).isEqualTo("preparing");
        }

        @Test
        @DisplayName("✅ status='hacked' (ไม่ valid) → 400 + ไม่บันทึก")
        void updateStatus_invalidStatus_rejects() {
            ResponseEntity<?> response = controller.updateStatus(
                    1L, Map.of("orderStatus", "hacked"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("สถานะไม่ถูกต้อง");
            verify(dineInOrderRepository, never()).findById(any());
            verify(dineInOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("status=null → 400")
        void updateStatus_nullStatus_rejects() {
            Map<String, String> req = new HashMap<>();
            req.put("orderStatus", null);

            ResponseEntity<?> response = controller.updateStatus(1L, req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(dineInOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("VALID_STATUSES ทั้งหมด: pending, preparing, ready, completed")
        void updateStatus_allValidStatuses() {
            DineInOrderEntity order = makeOrder(1L, "u@e.com", "5", "pending", "unpaid");
            when(dineInOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(dineInOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            for (String status : List.of("pending", "preparing", "ready", "completed")) {
                ResponseEntity<?> response = controller.updateStatus(
                        1L, Map.of("orderStatus", status));
                assertThat(response.getStatusCode())
                        .as("Status: %s ควรผ่าน", status)
                        .isEqualTo(HttpStatus.OK);
            }
        }

        @Test
        @DisplayName("Order ไม่พบ → 400")
        void updateStatus_orderNotFound_returns400() {
            when(dineInOrderRepository.findById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.updateStatus(
                    999L, Map.of("orderStatus", "ready"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("ไม่พบ");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/dinein/admin/orders/{id}/cancel — business rules")
    class CancelOrderTests {

        @Test
        @DisplayName("✅ ห้ามยกเลิก order ที่ completed แล้ว → 400")
        void cancel_completedOrder_rejects() {
            DineInOrderEntity completed = makeOrder(1L, "u@e.com", "5", "completed", "unpaid");
            when(dineInOrderRepository.findById(1L)).thenReturn(Optional.of(completed));

            ResponseEntity<?> response = controller.cancelOrder(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("เสร็จแล้ว");

            // ไม่ได้แตะ DB เพิ่มเติม
            verify(dineInOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("✅ ห้ามยกเลิก order ที่ paid แล้ว → 400")
        void cancel_paidOrder_rejects() {
            DineInOrderEntity paid = makeOrder(1L, "u@e.com", "5", "ready", "paid");
            when(dineInOrderRepository.findById(1L)).thenReturn(Optional.of(paid));

            ResponseEntity<?> response = controller.cancelOrder(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("ชำระเงินแล้ว");
            verify(dineInOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Order ไม่พบ → 400")
        void cancel_orderNotFound_returns400() {
            when(dineInOrderRepository.findById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.cancelOrder(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) castBody(response).get("message")).contains("ไม่พบ");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/dinein/admin/orders/{id}")
    class GetOrderDetailTests {

        @Test
        @DisplayName("Order มีอยู่ → 200 + items")
        void getOrderDetail_exists_returns200() {
            DineInOrderEntity order = makeOrder(1L, "u@e.com", "5", "pending", "unpaid");
            when(dineInOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(dineInOrderItemRepository.findByOrderId(1L))
                    .thenReturn(List.of(new DineInOrderItemEntity()));

            ResponseEntity<?> response = controller.getOrderDetail(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsKeys("order", "items");
        }

        @Test
        @DisplayName("Order ไม่มี → 404")
        void getOrderDetail_notFound_returns404() {
            when(dineInOrderRepository.findById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getOrderDetail(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/dinein/admin/orders — list all")
    class GetAllOrdersTests {

        @Test
        @DisplayName("คืน orders ทั้งหมดเรียงตามวันที่ล่าสุด")
        void getAllOrders_returnsAll() {
            DineInOrderEntity o1 = makeOrder(1L, "a@b.com", "5", "pending", "unpaid");
            DineInOrderEntity o2 = makeOrder(2L, "c@d.com", "7", "completed", "paid");

            when(dineInOrderRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(o1, o2));
            when(dineInOrderItemRepository.findByOrderId(any())).thenReturn(List.of());

            ResponseEntity<?> response = controller.getAllOrders();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody();
            assertThat(result).hasSize(2);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}