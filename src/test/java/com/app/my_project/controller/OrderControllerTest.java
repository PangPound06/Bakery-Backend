package com.app.my_project.controller;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test OrderController — focus on:
 *  - Auth checks (admin vs user)
 *  - Result enum → HTTP status mapping
 *  - Request parsing
 *  - Response shape
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock private OrderService orderService;
    @Mock private OrderItemService orderItemService;
    @Mock private OrderStatsService orderStatsService;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private StockService stockService;
    @Mock private JwtService jwtService;
    @Mock private AdminRepository adminRepository;

    @InjectMocks
    private OrderController controller;

    private static final String ADMIN_AUTH = "Bearer admin-token";
    private static final String USER_AUTH = "Bearer user-token";

    private void mockAdmin() {
        when(jwtService.isAdmin(ADMIN_AUTH)).thenReturn(true);
        when(jwtService.getUserIdFromHeader(ADMIN_AUTH)).thenReturn(1L);
        when(adminRepository.existsById(1L)).thenReturn(true);
    }

    private void mockNotAdmin() {
        when(jwtService.isAdmin(USER_AUTH)).thenReturn(false);
    }

    private OrderEntity makeOrder(Long id) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setEmail("user@test.com");
        o.setOrdCode("ORD123" + id);
        o.setOrderStatus("pending");
        o.setOrderType("online");
        return o;
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST / (createOrder)")
    class CreateOrderTests {

        @Test
        @DisplayName("Happy path: return orderId + ordCode")
        void create_success() {
            OrderEntity saved = makeOrder(1L);
            when(orderService.createOrder(eq("user@test.com"), any()))
                    .thenReturn(saved);

            Map<String, Object> body = new HashMap<>();
            body.put("email", "user@test.com");
            body.put("subtotal", 100.0);
            body.put("total", 130.0);
            body.put("items", List.of());

            ResponseEntity<?> response = controller.createOrder(body, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> resBody = (Map<String, Object>) response.getBody();
            assertThat(resBody.get("orderId")).isEqualTo(1L);
        }

        @Test
        @DisplayName("ไม่มี email → 400")
        void create_missingEmail_returns400() {
            ResponseEntity<?> response = controller.createOrder(new HashMap<>(), null);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(orderService);
        }

        @Test
        @DisplayName("Service throw → 500")
        void create_serviceThrows_returns500() {
            when(orderService.createOrder(any(), any())).thenThrow(new RuntimeException("DB error"));

            Map<String, Object> body = new HashMap<>();
            body.put("email", "u@t.com");

            ResponseEntity<?> response = controller.createOrder(body, null);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET endpoints")
    class GetTests {

        @Test
        @DisplayName("GET /user/{email} → return list")
        void getByEmail_returnsList() {
            when(orderService.getByEmail("a@b.com")).thenReturn(List.of(makeOrder(1L)));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());

            ResponseEntity<?> response = controller.getByEmail("a@b.com");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /{id} เจอ → 200 + order map")
        void getById_found() {
            when(orderService.getById(1L)).thenReturn(Optional.of(makeOrder(1L)));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());

            ResponseEntity<?> response = controller.getById(1L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            // ✅ verify wrapped format: { success, order, items }
            assertThat(body.get("success")).isEqualTo(true);
            assertThat(body.get("order")).isNotNull();
            assertThat(body.get("items")).isNotNull();
        }

        @Test
        @DisplayName("GET /{id} ไม่เจอ → 404")
        void getById_notFound() {
            when(orderService.getById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getById(999L);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("✅ GET /all admin → 200")
        void getAll_admin_200() {
            mockAdmin();
            when(orderService.getAll()).thenReturn(List.of(makeOrder(1L)));
            when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());

            ResponseEntity<?> response = controller.getAll(ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("✅ GET /all non-admin → 403")
        void getAll_nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.getAll(USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(orderService, never()).getAll();
        }

        @Test
        @DisplayName("GET /search/{code} เจอ → 200 with wrapped format")
        void search_found() {
            when(orderService.searchByCode("ORD1047291")).thenReturn(Optional.of(makeOrder(1L)));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());

            ResponseEntity<?> response = controller.searchByCode("ORD1047291");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("success")).isEqualTo(true);
            assertThat(body.get("order")).isNotNull();
        }

        @Test
        @DisplayName("GET /search/{code} ไม่เจอ → 404")
        void search_notFound() {
            when(orderService.searchByCode("BADCODE")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.searchByCode("BADCODE");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /{id}/status")
    class UpdateStatusTests {

        @Test
        @DisplayName("Admin + update success → 200")
        void admin_success() {
            mockAdmin();
            when(orderService.updateStatus(eq(1L), any(), any())).thenReturn(true);

            ResponseEntity<?> response = controller.updateStatus(1L,
                    Map.of("orderStatus", "shipping"), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Non-admin → 403")
        void nonAdmin_forbidden() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.updateStatus(1L,
                    Map.of("orderStatus", "shipping"), USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(orderService, never()).updateStatus(any(), any(), any());
        }

        @Test
        @DisplayName("Admin + order not found → 404")
        void admin_notFound() {
            mockAdmin();
            when(orderService.updateStatus(eq(999L), any(), any())).thenReturn(false);

            ResponseEntity<?> response = controller.updateStatus(999L,
                    Map.of("orderStatus", "shipping"), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /{id}/cancel")
    class CancelTests {

        @Test
        @DisplayName("SUCCESS → 200")
        void cancelSuccess_200() {
            when(orderService.cancelOrder(1L)).thenReturn(OrderService.CancelResult.SUCCESS);

            ResponseEntity<?> response = controller.cancelOrder(1L, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("NOT_FOUND → 404")
        void cancelNotFound_404() {
            when(orderService.cancelOrder(999L)).thenReturn(OrderService.CancelResult.NOT_FOUND);

            ResponseEntity<?> response = controller.cancelOrder(999L, null);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("✅ ALREADY_DELIVERED → 400 with message")
        void alreadyDelivered_400() {
            when(orderService.cancelOrder(1L)).thenReturn(OrderService.CancelResult.ALREADY_DELIVERED);

            ResponseEntity<?> response = controller.cancelOrder(1L, null);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /backfill-ordcode")
    class BackfillTests {

        @Test
        @DisplayName("Admin → 200 + count")
        void admin_returnsCount() {
            mockAdmin();
            when(orderService.backfillOrdCodes()).thenReturn(5);

            ResponseEntity<?> response = controller.backfillOrdCodes(ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("updatedCount")).isEqualTo(5);
        }

        @Test
        @DisplayName("Non-admin → 403")
        void nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.backfillOrdCodes(USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(orderService, never()).backfillOrdCodes();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /{orderId}/items")
    class AddItemTests {

        @Test
        @DisplayName("Admin + SUCCESS → 200 + itemId + totals")
        void admin_success() {
            mockAdmin();
            OrderItemEntity savedItem = new OrderItemEntity();
            savedItem.setId(100L);
            when(orderItemService.addItem(eq(1L), any()))
                    .thenReturn(new OrderItemService.AddItemResult(
                            OrderItemService.Result.SUCCESS,
                            savedItem,
                            new OrderItemService.Totals(100.0, 130.0)
                    ));

            Map<String, Object> body = new HashMap<>();
            body.put("productId", 10L);
            body.put("productName", "Cake");
            body.put("price", 100.0);
            body.put("displayQty", 1);

            ResponseEntity<?> response = controller.addItem(1L, body, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> resBody = (Map<String, Object>) response.getBody();
            assertThat(resBody.get("itemId")).isEqualTo(100L);
            assertThat(resBody.get("total")).isEqualTo(130.0);
        }

        @Test
        @DisplayName("Non-admin → 403")
        void nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.addItem(1L, new HashMap<>(), USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("Order not found → 404")
        void orderNotFound_404() {
            mockAdmin();
            when(orderItemService.addItem(any(), any()))
                    .thenReturn(new OrderItemService.AddItemResult(
                            OrderItemService.Result.ORDER_NOT_FOUND, null, null));

            ResponseEntity<?> response = controller.addItem(999L, new HashMap<>(), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("✅ Order locked → 400")
        void orderLocked_400() {
            mockAdmin();
            when(orderItemService.addItem(any(), any()))
                    .thenReturn(new OrderItemService.AddItemResult(
                            OrderItemService.Result.ORDER_LOCKED, null, null));

            ResponseEntity<?> response = controller.addItem(1L, new HashMap<>(), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PATCH /{orderId}/items/{itemId}")
    class UpdateItemTests {

        @Test
        @DisplayName("SUCCESS → 200 + totals")
        void success_returns200() {
            mockAdmin();
            when(orderItemService.updateQuantity(eq(1L), eq(100L), eq(2)))
                    .thenReturn(new OrderItemService.UpdateQtyResult(
                            OrderItemService.Result.SUCCESS,
                            new OrderItemService.Totals(200.0, 230.0)
                    ));

            ResponseEntity<?> response = controller.updateItemQty(1L, 100L,
                    Map.of("displayQty", 2), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("✅ ITEM_NOT_IN_ORDER → 400 with specific message")
        void itemNotInOrder_400() {
            mockAdmin();
            when(orderItemService.updateQuantity(any(), any(), anyInt()))
                    .thenReturn(new OrderItemService.UpdateQtyResult(
                            OrderItemService.Result.ITEM_NOT_IN_ORDER, null));

            ResponseEntity<?> response = controller.updateItemQty(1L, 100L,
                    Map.of("displayQty", 2), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("ITEM_NOT_FOUND → 404")
        void itemNotFound_404() {
            mockAdmin();
            when(orderItemService.updateQuantity(any(), any(), anyInt()))
                    .thenReturn(new OrderItemService.UpdateQtyResult(
                            OrderItemService.Result.ITEM_NOT_FOUND, null));

            ResponseEntity<?> response = controller.updateItemQty(1L, 999L,
                    Map.of("displayQty", 1), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("Non-admin → 403")
        void nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.updateItemQty(1L, 100L,
                    Map.of("displayQty", 1), USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /{orderId}/items/{itemId}")
    class RemoveItemTests {

        @Test
        @DisplayName("SUCCESS → 200")
        void success_returns200() {
            mockAdmin();
            when(orderItemService.removeItem(1L, 100L))
                    .thenReturn(new OrderItemService.RemoveItemResult(
                            OrderItemService.Result.SUCCESS,
                            new OrderItemService.Totals(50.0, 80.0)
                    ));

            ResponseEntity<?> response = controller.removeItem(1L, 100L, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("✅ ORDER_CANCELLED (ลบ item สุดท้าย) → 200 with cancel message")
        void orderCancelled_200WithMessage() {
            mockAdmin();
            when(orderItemService.removeItem(1L, 100L))
                    .thenReturn(new OrderItemService.RemoveItemResult(
                            OrderItemService.Result.ORDER_CANCELLED, null));

            ResponseEntity<?> response = controller.removeItem(1L, 100L, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("message").toString()).contains("cancelled");
        }

        @Test
        @DisplayName("Order not found → 404")
        void notFound_404() {
            mockAdmin();
            when(orderItemService.removeItem(any(), any()))
                    .thenReturn(new OrderItemService.RemoveItemResult(
                            OrderItemService.Result.ORDER_NOT_FOUND, null));

            ResponseEntity<?> response = controller.removeItem(999L, 100L, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("Non-admin → 403")
        void nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.removeItem(1L, 100L, USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /stats/top-products")
    class StatsTests {

        @Test
        @DisplayName("✅ Returns full response shape (no auth required)")
        void returnsResponse() {
            OrderStatsService.TopProduct top = new OrderStatsService.TopProduct(
                    "Cake", "1 ปอนด์", "cake", 5L, 500L, 3L
            );
            when(orderStatsService.getTopProducts("7"))
                    .thenReturn(new OrderStatsService.TopProductsResult(
                            List.of(top), 1000L, 10L, 5L, 1
                    ));

            ResponseEntity<?> response = controller.topProducts("7", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("totalAllRevenue")).isEqualTo(1000L);
            assertThat(body.get("topProducts")).isInstanceOf(List.class);
        }

        @Test
        @DisplayName("Default days = 'all'")
        void defaultDays_all() {
            when(orderStatsService.getTopProducts("all"))
                    .thenReturn(new OrderStatsService.TopProductsResult(
                            List.of(), 0L, 0L, 0L, 0
                    ));

            ResponseEntity<?> response = controller.topProducts("all", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(orderStatsService).getTopProducts("all");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("isAdmin() helper")
    class IsAdminTests {

        @Test
        @DisplayName("Token = admin role + adminRepo exists → true")
        void valid_admin_true() {
            when(jwtService.isAdmin(ADMIN_AUTH)).thenReturn(true);
            when(jwtService.getUserIdFromHeader(ADMIN_AUTH)).thenReturn(1L);
            when(adminRepository.existsById(1L)).thenReturn(true);

            assertThat(controller.isAdmin(ADMIN_AUTH)).isTrue();
        }

        @Test
        @DisplayName("✅ Token = admin role + adminRepo NOT exists → false (stale token)")
        void adminRoleButDeleted_false() {
            when(jwtService.isAdmin(anyString())).thenReturn(true);
            when(jwtService.getUserIdFromHeader(anyString())).thenReturn(99L);
            when(adminRepository.existsById(99L)).thenReturn(false);

            assertThat(controller.isAdmin("Bearer stale")).isFalse();
        }

        @Test
        @DisplayName("Token = user role → false")
        void userRole_false() {
            when(jwtService.isAdmin(anyString())).thenReturn(false);

            assertThat(controller.isAdmin("Bearer user")).isFalse();
        }

        @Test
        @DisplayName("null auth → false (no NPE)")
        void nullAuth_false() {
            when(jwtService.isAdmin(null)).thenReturn(false);

            assertThat(controller.isAdmin(null)).isFalse();
        }
    }
}