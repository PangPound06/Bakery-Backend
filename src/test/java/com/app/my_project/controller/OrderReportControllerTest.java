package com.app.my_project.controller;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.OrderReportService;
import com.app.my_project.service.OrderReportService.OrderFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test OrderReportController — focus on:
 * - Auth check (admin → ok, ไม่ใช่ admin → 403)
 * - การประกอบ body แบบ paginate + แนบ items
 * - การส่ง filter และการแปลงวันที่ (date-only vs full datetime)
 */
@ExtendWith(MockitoExtension.class)
class OrderReportControllerTest {

    @Mock
    private OrderReportService reportService;
    @Mock
    private JwtService jwtService;
    @Mock
    private AdminRepository adminRepository;

    @InjectMocks
    private OrderReportController controller;

    private static final String ADMIN_AUTH = "Bearer admin-token";
    private static final String BAD_AUTH = "Bearer not-admin";

    private void mockAdmin() {
        when(jwtService.isAdmin(ADMIN_AUTH)).thenReturn(true);
        when(jwtService.getUserIdFromHeader(ADMIN_AUTH)).thenReturn(1L);
        when(adminRepository.existsById(1L)).thenReturn(true);
    }

    private OrderEntity order(Long id) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setOrdCode("ORD" + id);
        o.setEmail("u@test.com");
        o.setOrderStatus("delivered");
        o.setOrderType("online");
        return o;
    }

    // ════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /admin/page")
    class PageEndpoint {

        @Test
        @DisplayName("ไม่ใช่ admin → 403 และไม่เรียก service")
        void forbidden() {
            when(jwtService.isAdmin(BAD_AUTH)).thenReturn(false);

            ResponseEntity<?> resp =
                    controller.page(BAD_AUTH, 0, 50, null, null, null, null, null);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("admin → คืน body paginate ครบ field + แนบ items ตาม orderId")
        @SuppressWarnings("unchecked")
        void adminReturnsPagedBody() {
            mockAdmin();
            Page<OrderEntity> page =
                    new PageImpl<>(List.of(order(1L)), PageRequest.of(0, 50), 1);
            when(reportService.pagedSearch(any(OrderFilter.class), eq(0), eq(50)))
                    .thenReturn(page);
            when(reportService.idsOf(anyList())).thenReturn(List.of(1L));
            when(reportService.itemsByOrderIds(anyList()))
                    .thenReturn(Map.of(1L, List.of(
                            Map.of("id", 10L, "productName", "เค้ก"))));

            ResponseEntity<?> resp =
                    controller.page(ADMIN_AUTH, 0, 50, null, null, null, null, null);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertThat(body.get("page")).isEqualTo(0);
            assertThat(body.get("size")).isEqualTo(50);
            assertThat(body.get("totalElements")).isEqualTo(1L);
            assertThat(body.get("totalPages")).isEqualTo(1);

            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) body.get("content");
            assertThat(content).hasSize(1);
            assertThat(content.get(0))
                    .containsEntry("ordCode", "ORD1")
                    .containsEntry("email", "u@test.com");
            assertThat((List<?>) content.get(0).get("items")).hasSize(1);
        }

        @Test
        @DisplayName("ส่ง filter + แปลงวันที่แบบ date-only (from→00:00:00, to→23:59:59)")
        void passesFilterAndParsesDateOnly() {
            mockAdmin();
            when(reportService.pagedSearch(any(OrderFilter.class), anyInt(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of()));
            when(reportService.idsOf(anyList())).thenReturn(List.of());
            when(reportService.itemsByOrderIds(anyList())).thenReturn(Map.of());

            controller.page(ADMIN_AUTH, 2, 30, "delivered", "pos", "abc",
                    "2026-05-01", "2026-05-31");

            ArgumentCaptor<OrderFilter> cap = ArgumentCaptor.forClass(OrderFilter.class);
            verify(reportService).pagedSearch(cap.capture(), eq(2), eq(30));
            OrderFilter f = cap.getValue();
            assertThat(f.status).isEqualTo("delivered");
            assertThat(f.channel).isEqualTo("pos");
            assertThat(f.search).isEqualTo("abc");
            assertThat(f.from).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0, 0));
            assertThat(f.to).isEqualTo(LocalDateTime.of(2026, 5, 31, 23, 59, 59));
        }

        @Test
        @DisplayName("ไม่ส่งวันที่ → from/to เป็น null")
        void nullDatesBecomeNull() {
            mockAdmin();
            when(reportService.pagedSearch(any(OrderFilter.class), anyInt(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of()));
            when(reportService.idsOf(anyList())).thenReturn(List.of());
            when(reportService.itemsByOrderIds(anyList())).thenReturn(Map.of());

            controller.page(ADMIN_AUTH, 0, 50, null, null, null, null, null);

            ArgumentCaptor<OrderFilter> cap = ArgumentCaptor.forClass(OrderFilter.class);
            verify(reportService).pagedSearch(cap.capture(), anyInt(), anyInt());
            assertThat(cap.getValue().from).isNull();
            assertThat(cap.getValue().to).isNull();
        }
    }

    // ════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /admin/report-summary")
    class ReportSummaryEndpoint {

        @Test
        @DisplayName("ไม่ใช่ admin → 403")
        void forbidden() {
            when(jwtService.isAdmin(BAD_AUTH)).thenReturn(false);

            ResponseEntity<?> resp = controller.reportSummary(BAD_AUTH, null, null);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("admin → คืนผลจาก summary + แปลงวันที่แบบ full datetime")
        @SuppressWarnings("unchecked")
        void adminReturnsSummary() {
            mockAdmin();
            Map<String, Object> summary =
                    Map.of("totalRevenue", 8000.0, "validOrderCount", 15L);
            when(reportService.summary(any(), any())).thenReturn(summary);

            ResponseEntity<?> resp = controller.reportSummary(ADMIN_AUTH,
                    "2026-05-01T10:30:00", "2026-05-02T20:00:00");

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat((Map<String, Object>) resp.getBody()).isSameAs(summary);

            ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(reportService).summary(fromCap.capture(), toCap.capture());
            assertThat(fromCap.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 1, 10, 30, 0));
            assertThat(toCap.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 2, 20, 0, 0));
        }
    }
}