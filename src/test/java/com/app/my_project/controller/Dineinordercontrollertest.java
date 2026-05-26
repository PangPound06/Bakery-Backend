package com.app.my_project.controller;

import com.app.my_project.entity.DineInOrderEntity;
import com.app.my_project.entity.DineInOrderItemEntity;
import com.app.my_project.repository.DineInOrderItemRepository;
import com.app.my_project.repository.DineInOrderRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test DineInOrderController
 *
 * Focus:
 *  - createOrder: ดึง email จาก SecurityContext, default status=pending
 *  - getMyOrders: filter ออเดอร์ active เท่านั้น (ไม่ใช่ cancelled/paid)
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
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;

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
    @DisplayName("POST /api/dinein/orders")
    class CreateOrderTests {

        private Map<String, Object> createRequest() {
            Map<String, Object> req = new HashMap<>();
            req.put("tableNo", "5");
            req.put("subtotal", 100.0);
            req.put("total", 100.0);
            req.put("items", List.of()); // จะ skip ลด stock
            return req;
        }

        @Test
        @DisplayName("✅ Happy path: สร้าง order → email จาก token, status=pending")
        void createOrder_validRequest_setsEmailAndPendingStatus() {
            mockUser("user@example.com");
            when(dineInOrderRepository.save(any())).thenAnswer(inv -> {
                DineInOrderEntity e = inv.getArgument(0);
                e.setId(1L);
                return e;
            });

            ResponseEntity<?> response = controller.createOrder(createRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            ArgumentCaptor<DineInOrderEntity> captor =
                    ArgumentCaptor.forClass(DineInOrderEntity.class);
            verify(dineInOrderRepository, atLeastOnce()).save(captor.capture());
            DineInOrderEntity saved = captor.getValue();
            assertThat(saved.getEmail()).isEqualTo("user@example.com");
            assertThat(saved.getOrderStatus()).isEqualTo("pending");
            assertThat(saved.getTableNo()).isEqualTo("5");
        }

        @Test
        @DisplayName("subtotal=null → exception → 400")
        void createOrder_missingSubtotal_returns400() {
            mockUser("user@example.com");
            Map<String, Object> req = new HashMap<>();
            req.put("tableNo", "5");
            req.put("total", 100.0);
            // ไม่มี subtotal

            ResponseEntity<?> response = controller.createOrder(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/dinein/my-orders — active orders only")
    class GetMyOrdersTests {

        @Test
        @DisplayName("✅ คืนเฉพาะ order ที่ active (ไม่ใช่ cancelled, ไม่ใช่ paid)")
        void getMyOrders_filtersActiveOnly() {
            mockUser("user@example.com");

            DineInOrderEntity active = makeOrder(1L, "user@example.com", "5", "pending", "unpaid");
            DineInOrderEntity cancelled = makeOrder(2L, "user@example.com", "5", "cancelled", "unpaid");
            DineInOrderEntity paid = makeOrder(3L, "user@example.com", "5", "completed", "paid");

            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(List.of(active, cancelled, paid));
            when(dineInOrderItemRepository.findByOrderId(any())).thenReturn(List.of());

            ResponseEntity<?> response = controller.getMyOrders(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody();

            // เฉพาะ active 1 รายการ
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsEntry("id", 1L);
        }

        @Test
        @DisplayName("filter ตาม tableNo → คืนเฉพาะที่ตรง")
        void getMyOrders_filterByTable() {
            mockUser("user@example.com");

            DineInOrderEntity table5 = makeOrder(1L, "user@example.com", "5", "pending", "unpaid");
            DineInOrderEntity table7 = makeOrder(2L, "user@example.com", "7", "pending", "unpaid");

            when(dineInOrderRepository.findByEmailOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(List.of(table5, table7));
            when(dineInOrderItemRepository.findByOrderId(any())).thenReturn(List.of());

            ResponseEntity<?> response = controller.getMyOrders("5");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody();
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsEntry("tableNo", "5");
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