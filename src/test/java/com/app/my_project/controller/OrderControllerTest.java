package com.app.my_project.controller;

import com.app.my_project.common.CurrentUser;
import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
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
import org.springframework.data.domain.PageImpl;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test OrderController — focus on:
 * - Auth checks (admin vs user)
 * - Result enum → HTTP status mapping
 * - Request parsing
 * - Response shape
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;
    @Mock
    private OrderItemService orderItemService;
    @Mock
    private OrderStatsService orderStatsService;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private StockService stockService;
    @Mock
    private CurrentUser currentUser;
    @Mock
    private StoreSettingService storeSettingService;
    @Mock
    private RefundService refundService;
    @Mock
    private PaymentStateService paymentStateService;
    @Mock
    private PaymentReconciler paymentReconciler;

    @InjectMocks
    private OrderController controller;

    private static final String ADMIN_AUTH = "Bearer admin-token";
    private static final String USER_AUTH = "Bearer user-token";

    private void mockAdmin() {
        when(currentUser.isAdmin(ADMIN_AUTH)).thenReturn(true);
    }

    private void mockNotAdmin() {
        when(currentUser.isAdmin(USER_AUTH)).thenReturn(false);
    }

    /** ลูกค้า user@test.com (เจ้าของ makeOrder) */
    private void mockOwner() {
        when(currentUser.isAdminOrOwner(USER_AUTH, "user@test.com")).thenReturn(true);
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
        @DisplayName("แอดมิน (POS) สร้างออเดอร์แทนลูกค้า → return orderId + ordCode")
        void create_success() {
            mockAdmin();
            OrderEntity saved = makeOrder(1L);
            when(orderService.createOrder(eq("user@test.com"), any()))
                    .thenReturn(saved);

            Map<String, Object> body = new HashMap<>();
            body.put("email", "user@test.com");
            body.put("subtotal", 100.0);
            body.put("total", 130.0);
            body.put("items", List.of());

            ResponseEntity<?> response = controller.createOrder(body, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> resBody = (Map<String, Object>) response.getBody();
            assertThat(resBody.get("orderId")).isEqualTo(1L);
        }

        @Test
        @DisplayName("แอดมินไม่ส่ง email → 400")
        void create_missingEmail_returns400() {
            mockAdmin();

            ResponseEntity<?> response = controller.createOrder(new HashMap<>(), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(orderService);
        }

        @Test
        @DisplayName("ไม่มี token → 401 (ลูกค้าต้องล็อกอิน และ email มาจาก token เท่านั้น)")
        void create_withoutToken_returns401() {
            when(currentUser.customerEmail(null)).thenReturn(Optional.empty());

            Map<String, Object> body = new HashMap<>();
            body.put("email", "someone-else@test.com");

            ResponseEntity<?> response = controller.createOrder(body, null);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verifyNoInteractions(orderService);
        }

        @Test
        @DisplayName("Service throw → 500")
        void create_serviceThrows_returns500() {
            mockAdmin();
            when(orderService.createOrder(any(), any())).thenThrow(new RuntimeException("DB error"));

            Map<String, Object> body = new HashMap<>();
            body.put("email", "u@t.com");

            ResponseEntity<?> response = controller.createOrder(body, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET endpoints")
    class GetTests {

        @Test
        @DisplayName("GET /user/{email} เจ้าของ → return list")
        void getByEmail_owner_returnsList() {
            when(currentUser.isAdminOrOwner(USER_AUTH, "a@b.com")).thenReturn(true);
            when(orderService.getByEmail(eq("a@b.com"), any()))
                    .thenReturn(new PageImpl<>(List.of(makeOrder(1L))));
            when(orderItemRepository.findByOrderIdIn(any())).thenReturn(List.of());

            ResponseEntity<?> response = controller.getByEmail("a@b.com", USER_AUTH, 0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("GET /user/{email} ของคนอื่น / ไม่ login → 403 (ไม่ให้เห็นชื่อ เบอร์ ที่อยู่)")
        void getByEmail_otherCustomer_forbidden() {
            when(currentUser.isAdminOrOwner(any(), eq("victim@test.com"))).thenReturn(false);

            assertThat(controller.getByEmail("victim@test.com", USER_AUTH, 0, 20).getStatusCode().value())
                    .isEqualTo(403);
            assertThat(controller.getByEmail("victim@test.com", null, 0, 20).getStatusCode().value())
                    .isEqualTo(403);
            verify(orderService, never()).getByEmail(any(), any());
        }

        @Test
        @DisplayName("GET /{id} เจ้าของ → 200 + order map")
        void getById_found() {
            mockOwner();
            when(orderService.getById(1L)).thenReturn(Optional.of(makeOrder(1L)));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());

            ResponseEntity<?> response = controller.getById(1L, USER_AUTH);

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

            ResponseEntity<?> response = controller.getById(999L, USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("GET /{id} order ของคนอื่น → 404 (ไม่บอกว่ามี id นี้อยู่จริง)")
        void getById_otherCustomer_404() {
            when(orderService.getById(1L)).thenReturn(Optional.of(makeOrder(1L)));
            when(currentUser.isAdminOrOwner(USER_AUTH, "user@test.com")).thenReturn(false);

            assertThat(controller.getById(1L, USER_AUTH).getStatusCode().value()).isEqualTo(404);
            verifyNoInteractions(orderItemRepository);
        }

        @Test
        @DisplayName("✅ GET /all admin → 200")
        void getAll_admin_200() {
            mockAdmin();
            when(orderService.getAll(any())).thenReturn(new PageImpl<>(List.of(makeOrder(1L))));
            when(orderItemRepository.findByOrderIdIn(any())).thenReturn(List.of());

            ResponseEntity<?> response = controller.getAll(ADMIN_AUTH, 0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("✅ GET /all non-admin → 403")
        void getAll_nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.getAll(USER_AUTH, 0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(orderService, never()).getAll(any());
        }

        @Test
        @DisplayName("GET /search/{code} เจ้าของ → 200 พร้อมข้อมูลติดต่อครบ")
        void search_found() {
            mockOwner();
            OrderEntity order = makeOrder(1L);
            order.setReceiverPhone("0812345678");
            order.setReceiverAddress("1 ถนนสุขุมวิท");
            when(orderService.searchByCode("ORD1047291")).thenReturn(Optional.of(order));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());

            ResponseEntity<?> response = controller.searchByCode("ORD1047291", USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("success")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> orderMap = (Map<String, Object>) body.get("order");
            assertThat(orderMap).containsEntry("receiverPhone", "0812345678")
                    .containsEntry("receiverAddress", "1 ถนนสุขุมวิท");
        }

        @Test
        @DisplayName("GET /search/{code} คนอื่น → เห็นสถานะ แต่ซ่อน email ที่อยู่ สลิป และเบอร์")
        void search_stranger_hidesContact() {
            OrderEntity order = makeOrder(1L);
            order.setReceiverPhone("0812345678");
            order.setReceiverAddress("1 ถนนสุขุมวิท");
            order.setSlipImage("https://res.cloudinary.com/slip.png");
            when(orderService.searchByCode("ORD1047291")).thenReturn(Optional.of(order));
            when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());
            when(currentUser.isAdminOrOwner(null, "user@test.com")).thenReturn(false);

            ResponseEntity<?> response = controller.searchByCode("ORD1047291", null);

            @SuppressWarnings("unchecked")
            Map<String, Object> orderMap = (Map<String, Object>) ((Map<String, Object>) response.getBody())
                    .get("order");
            assertThat(orderMap)
                    .containsEntry("orderStatus", "pending")
                    .containsEntry("receiverPhone", "xxxxxx5678")
                    .containsEntry("receiverAddress", null)
                    .containsEntry("email", null)
                    .containsEntry("slipImage", null);
        }

        @Test
        @DisplayName("GET /search/{code} ไม่เจอ → 404")
        void search_notFound() {
            when(orderService.searchByCode("BADCODE")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.searchByCode("BADCODE", null);

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
            when(orderService.updateStatus(eq(1L), any(), any()))
                    .thenReturn(OrderService.UpdateStatusResult.SUCCESS);

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
            when(orderService.updateStatus(eq(999L), any(), any()))
                    .thenReturn(OrderService.UpdateStatusResult.NOT_FOUND);

            ResponseEntity<?> response = controller.updateStatus(999L,
                    Map.of("orderStatus", "shipping"), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("ยกเลิกผ่านทางนี้ → 400 / order ออนไลน์ยังไม่ได้เงิน → 409")
        void admin_rejectedTransitions() {
            mockAdmin();
            when(orderService.updateStatus(1L, "cancelled", null))
                    .thenReturn(OrderService.UpdateStatusResult.USE_CANCEL);
            when(orderService.updateStatus(1L, "confirmed", null))
                    .thenReturn(OrderService.UpdateStatusResult.PAYMENT_REQUIRED);

            assertThat(controller.updateStatus(1L, Map.of("orderStatus", "cancelled"), ADMIN_AUTH)
                    .getStatusCode().value()).isEqualTo(400);
            assertThat(controller.updateStatus(1L, Map.of("orderStatus", "confirmed"), ADMIN_AUTH)
                    .getStatusCode().value()).isEqualTo(409);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /{id}/cancel")
    class CancelTests {

        private void ownOrder(OrderEntity order) {
            when(orderService.getById(order.getId())).thenReturn(Optional.of(order));
            mockOwner();
        }

        @Test
        @DisplayName("SUCCESS → 200")
        void cancelSuccess_200() {
            ownOrder(makeOrder(1L));
            when(orderService.cancelOrder(1L)).thenReturn(OrderService.CancelResult.SUCCESS);

            ResponseEntity<?> response = controller.cancelOrder(1L, USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("ไม่มี order / order ของคนอื่น / ไม่ login → 404 และไม่แตะ stock")
        void cancelNotOwned_404() {
            when(orderService.getById(999L)).thenReturn(Optional.empty());
            when(orderService.getById(1L)).thenReturn(Optional.of(makeOrder(1L)));
            when(currentUser.isAdminOrOwner(any(), eq("user@test.com"))).thenReturn(false);

            assertThat(controller.cancelOrder(999L, USER_AUTH).getStatusCode().value()).isEqualTo(404);
            assertThat(controller.cancelOrder(1L, USER_AUTH).getStatusCode().value()).isEqualTo(404);
            assertThat(controller.cancelOrder(1L, null).getStatusCode().value()).isEqualTo(404);
            verify(orderService, never()).cancelOrder(any());
        }

        @Test
        @DisplayName("✅ ALREADY_DELIVERED → 400 with message")
        void alreadyDelivered_400() {
            ownOrder(makeOrder(1L));
            when(orderService.cancelOrder(1L)).thenReturn(OrderService.CancelResult.ALREADY_DELIVERED);

            ResponseEntity<?> response = controller.cancelOrder(1L, USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("แจ้งโอนแล้ว รอแอดมินตรวจ → 409")
        void awaitingVerification_409() {
            ownOrder(makeOrder(1L));
            when(orderService.cancelOrder(1L)).thenReturn(OrderService.CancelResult.AWAITING_VERIFICATION);

            assertThat(controller.cancelOrder(1L, USER_AUTH).getStatusCode().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("ยังรอจ่าย → ปิด QR กับ Stripe ก่อน แล้วลบรอบที่ไม่ได้เงินทิ้ง")
        void awaitingPayment_settlesWithStripeFirst() {
            OrderEntity order = makeOrder(1L);
            order.setPaymentStatus(PaymentStateService.AWAITING);
            ownOrder(order);
            when(paymentReconciler.settle(order)).thenReturn(PaymentReconciler.Outcome.UNPAID);

            assertThat(controller.cancelOrder(1L, USER_AUTH).getStatusCode().value()).isEqualTo(200);
            verify(paymentStateService).discardUnpaid(eq(1L), any());
            verify(orderService, never()).cancelOrder(any());
        }

        @Test
        @DisplayName("ยังรอจ่าย แต่ Stripe บอกว่าลูกค้าจ่ายแล้ว → 409 ต้องขอคืนเงิน")
        void awaitingPayment_butPaid_409() {
            OrderEntity order = makeOrder(1L);
            order.setPaymentStatus(PaymentStateService.AWAITING);
            ownOrder(order);
            when(paymentReconciler.settle(order)).thenReturn(PaymentReconciler.Outcome.PAID);

            assertThat(controller.cancelOrder(1L, USER_AUTH).getStatusCode().value()).isEqualTo(409);
            verify(paymentStateService, never()).discardUnpaid(any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /{id}/transfer/confirm|reject")
    class TransferTests {

        @Test
        @DisplayName("แอดมินยืนยันยอดโอน → 200 และบันทึกว่าใครยืนยัน")
        void confirm_admin_200() {
            when(currentUser.adminEmail(ADMIN_AUTH)).thenReturn(Optional.of("boss@empbakery.com"));
            when(paymentStateService.confirmTransfer(1L, "boss@empbakery.com"))
                    .thenReturn(PaymentStateService.TransferResult.OK);

            assertThat(controller.confirmTransfer(1L, ADMIN_AUTH).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("ไม่ใช่แอดมิน → 403 ยืนยัน/ปฏิเสธไม่ได้")
        void nonAdmin_403() {
            when(currentUser.adminEmail(USER_AUTH)).thenReturn(Optional.empty());

            assertThat(controller.confirmTransfer(1L, USER_AUTH).getStatusCode().value()).isEqualTo(403);
            assertThat(controller.rejectTransfer(1L, Map.of(), USER_AUTH).getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(paymentStateService);
        }

        @Test
        @DisplayName("ปฏิเสธพร้อมเหตุผล → ส่งเหตุผลต่อให้ service")
        void reject_passesReason() {
            when(currentUser.adminEmail(ADMIN_AUTH)).thenReturn(Optional.of("boss@empbakery.com"));
            when(paymentStateService.rejectTransfer(1L, "ยอดไม่ตรง", "boss@empbakery.com"))
                    .thenReturn(PaymentStateService.TransferResult.OK);

            assertThat(controller.rejectTransfer(1L, Map.of("reason", "ยอดไม่ตรง"), ADMIN_AUTH)
                    .getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("ผลจาก service → HTTP status")
        void mapsResults() {
            when(currentUser.adminEmail(ADMIN_AUTH)).thenReturn(Optional.of("boss@empbakery.com"));
            when(paymentStateService.confirmTransfer(eq(1L), any()))
                    .thenReturn(PaymentStateService.TransferResult.NOT_FOUND);
            when(paymentStateService.confirmTransfer(eq(2L), any()))
                    .thenReturn(PaymentStateService.TransferResult.NOT_TRANSFER);
            when(paymentStateService.confirmTransfer(eq(3L), any()))
                    .thenReturn(PaymentStateService.TransferResult.WRONG_STATE);

            assertThat(controller.confirmTransfer(1L, ADMIN_AUTH).getStatusCode().value()).isEqualTo(404);
            assertThat(controller.confirmTransfer(2L, ADMIN_AUTH).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.confirmTransfer(3L, ADMIN_AUTH).getStatusCode().value()).isEqualTo(409);
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
        @DisplayName("Admin + SUCCESS → 200 + newItem + newSubtotal + newTotal")
        void admin_success() {
            mockAdmin();
            OrderItemEntity savedItem = new OrderItemEntity();
            savedItem.setId(100L);
            savedItem.setProductId(10L);
            savedItem.setProductName("Cake");
            savedItem.setPrice(100.0);
            savedItem.setQuantity(1);
            when(orderItemService.addItem(eq(1L), any()))
                    .thenReturn(new OrderItemService.AddItemResult(
                            OrderItemService.Result.SUCCESS,
                            savedItem,
                            new OrderItemService.Totals(100.0, 130.0)));

            Map<String, Object> body = new HashMap<>();
            body.put("productId", 10L);
            body.put("productName", "Cake");
            body.put("price", 100.0);
            body.put("quantity", 1);

            ResponseEntity<?> response = controller.addItem(1L, body, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> resBody = (Map<String, Object>) response.getBody();
            // ✅ verify frontend-compatible shape
            assertThat(resBody.get("success")).isEqualTo(true);
            assertThat(resBody.get("newItem")).isNotNull();
            assertThat(resBody.get("newSubtotal")).isEqualTo(100.0);
            assertThat(resBody.get("newTotal")).isEqualTo(130.0);
            @SuppressWarnings("unchecked")
            Map<String, Object> newItem = (Map<String, Object>) resBody.get("newItem");
            assertThat(newItem.get("id")).isEqualTo(100L);
            assertThat(newItem.get("productName")).isEqualTo("Cake");
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
        @DisplayName("SUCCESS → 200 + newSubtotal + newTotal")
        void success_returns200() {
            mockAdmin();
            when(orderItemService.updateQuantity(eq(1L), eq(100L), eq(2)))
                    .thenReturn(new OrderItemService.UpdateQtyResult(
                            OrderItemService.Result.SUCCESS,
                            new OrderItemService.Totals(200.0, 230.0)));

            ResponseEntity<?> response = controller.updateItemQty(1L, 100L,
                    Map.of("quantity", 2), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("newSubtotal")).isEqualTo(200.0);
            assertThat(body.get("newTotal")).isEqualTo(230.0);
        }

        @Test
        @DisplayName("✅ ITEM_NOT_IN_ORDER → 400 with specific message")
        void itemNotInOrder_400() {
            mockAdmin();
            when(orderItemService.updateQuantity(any(), any(), anyInt()))
                    .thenReturn(new OrderItemService.UpdateQtyResult(
                            OrderItemService.Result.ITEM_NOT_IN_ORDER, null));

            ResponseEntity<?> response = controller.updateItemQty(1L, 100L,
                    Map.of("quantity", 2), ADMIN_AUTH);

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
                    Map.of("quantity", 1), ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("Non-admin → 403")
        void nonAdmin_403() {
            mockNotAdmin();

            ResponseEntity<?> response = controller.updateItemQty(1L, 100L,
                    Map.of("quantity", 1), USER_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /{orderId}/items/{itemId}")
    class RemoveItemTests {

        @Test
        @DisplayName("SUCCESS → 200 + newSubtotal + newTotal")
        void success_returns200() {
            mockAdmin();
            when(orderItemService.removeItem(1L, 100L))
                    .thenReturn(new OrderItemService.RemoveItemResult(
                            OrderItemService.Result.SUCCESS,
                            new OrderItemService.Totals(50.0, 80.0)));

            ResponseEntity<?> response = controller.removeItem(1L, 100L, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("newSubtotal")).isEqualTo(50.0);
            assertThat(body.get("newTotal")).isEqualTo(80.0);
        }

        @Test
        @DisplayName("✅ ORDER_CANCELLED (ลบ item สุดท้าย) → 200 with cancelled flag + message")
        void orderCancelled_200WithMessage() {
            mockAdmin();
            when(orderItemService.removeItem(1L, 100L))
                    .thenReturn(new OrderItemService.RemoveItemResult(
                            OrderItemService.Result.ORDER_CANCELLED, null));

            ResponseEntity<?> response = controller.removeItem(1L, 100L, ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("cancelled")).isEqualTo(true);
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
        @DisplayName("✅ แอดมิน → ได้ยอดขายครบ")
        void admin_seesRevenue() {
            mockAdmin();
            OrderStatsService.TopProduct top = new OrderStatsService.TopProduct(
                    "Cake", "1 ปอนด์", "cake", 5L, 500L, 3L);
            when(orderStatsService.getTopProducts("7"))
                    .thenReturn(new OrderStatsService.TopProductsResult(
                            List.of(top), 1000L, 10L, 5L, 1));

            ResponseEntity<?> response = controller.topProducts("7", ADMIN_AUTH);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("totalAllRevenue")).isEqualTo(1000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> products = (List<Map<String, Object>>) body.get("topProducts");
            assertThat(products.get(0)).containsEntry("totalRevenue", 500L);
        }

        @Test
        @DisplayName("✅ หน้าร้าน (ไม่ใช่แอดมิน) → เห็นสินค้าขายดี แต่ไม่เห็นยอดขาย")
        void public_hidesRevenue() {
            OrderStatsService.TopProduct top = new OrderStatsService.TopProduct(
                    "Cake", "1 ปอนด์", "cake", 5L, 500L, 3L);
            when(orderStatsService.getTopProducts("7"))
                    .thenReturn(new OrderStatsService.TopProductsResult(
                            List.of(top), 1000L, 10L, 5L, 1));

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) controller.topProducts("7", null).getBody();

            assertThat(body).doesNotContainKey("totalAllRevenue");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> products = (List<Map<String, Object>>) body.get("topProducts");
            assertThat(products.get(0)).containsEntry("productName", "Cake").doesNotContainKey("totalRevenue");
        }

        @Test
        @DisplayName("Default days = 'all'")
        void defaultDays_all() {
            when(orderStatsService.getTopProducts("all"))
                    .thenReturn(new OrderStatsService.TopProductsResult(
                            List.of(), 0L, 0L, 0L, 0));

            ResponseEntity<?> response = controller.topProducts("all", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(orderStatsService).getTopProducts("all");
        }
    }
}
