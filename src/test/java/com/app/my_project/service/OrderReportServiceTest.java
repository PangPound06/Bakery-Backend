package com.app.my_project.service;

import com.app.my_project.entity.OrderEntity;
import com.app.my_project.entity.OrderItemEntity;
import com.app.my_project.repository.OrderItemRepository;
import com.app.my_project.repository.OrderRepository;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReportServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private OrderReportService service;

    private OrderItemEntity item(Long id, Long orderId, String name) {
        OrderItemEntity i = new OrderItemEntity();
        i.setId(id);
        i.setOrderId(orderId);
        i.setProductId(99L);
        i.setProductName(name);
        i.setPrice(50.0);
        i.setQuantity(2);
        i.setSelectedOption(null);
        return i;
    }

    @Nested
    @DisplayName("pagedSearch")
    class PagedSearch {
        @SuppressWarnings("unchecked")
        private void stubFindAll() {
            when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
        }

        @SuppressWarnings("unchecked")
        private Pageable capturePageable() {
            ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
            verify(orderRepository).findAll(any(Specification.class), cap.capture());
            return cap.getValue();
        }

        @Test
        @DisplayName("size <= 0 → ใช้ค่า default 50")
        void defaultSize() {
            stubFindAll();
            service.pagedSearch(new OrderFilter(), 0, 0);
            assertThat(capturePageable().getPageSize()).isEqualTo(50);
        }

        @Test
        @DisplayName("size เกิน 200 → ถูก clamp เหลือ 200")
        void clampMax() {
            stubFindAll();
            service.pagedSearch(new OrderFilter(), 0, 500);
            assertThat(capturePageable().getPageSize()).isEqualTo(200);
        }

        @Test
        @DisplayName("size ปกติ → ใช้ตามที่ส่งมา และ sort createdAt DESC")
        void normalSize() {
            stubFindAll();
            service.pagedSearch(new OrderFilter(), 2, 30);
            Pageable p = capturePageable();
            assertThat(p.getPageSize()).isEqualTo(30);
            assertThat(p.getPageNumber()).isEqualTo(2);
            assertThat(p.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(p.getSort().getOrderFor("createdAt").isDescending()).isTrue();
        }

        @Test
        @DisplayName("page ติดลบ → ถูกปรับเป็น 0")
        void negativePage() {
            stubFindAll();
            service.pagedSearch(new OrderFilter(), -5, 50);
            assertThat(capturePageable().getPageNumber()).isZero();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("คืน Page ที่ได้จาก repository")
        void returnsRepoPage() {
            Page<OrderEntity> page = new PageImpl<>(List.of(new OrderEntity()));
            when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);
            assertThat(service.pagedSearch(new OrderFilter(), 0, 50)).isSameAs(page);
        }
    }

    @Nested
    @DisplayName("itemsByOrderIds")
    class ItemsByOrderIds {
        @Test
        @DisplayName("list ว่าง/null → คืน map ว่าง และไม่แตะ DB")
        void emptyInput() {
            assertThat(service.itemsByOrderIds(List.of())).isEmpty();
            assertThat(service.itemsByOrderIds(null)).isEmpty();
            verifyNoInteractions(orderItemRepository);
        }

        @Test
        @DisplayName("group items ตาม orderId ถูกต้อง")
        void groupsByOrderId() {
            when(orderItemRepository.findByOrderIdIn(List.of(1L, 2L)))
                    .thenReturn(List.of(
                            item(10L, 1L, "เค้ก"),
                            item(11L, 1L, "กาแฟ"),
                            item(12L, 2L, "ชา")));

            Map<Long, List<Map<String, Object>>> result =
                    service.itemsByOrderIds(List.of(1L, 2L));

            assertThat(result).containsOnlyKeys(1L, 2L);
            assertThat(result.get(1L)).hasSize(2);
            assertThat(result.get(2L)).hasSize(1);
            assertThat(result.get(1L).get(0))
                    .containsEntry("id", 10L)
                    .containsEntry("productName", "เค้ก")
                    .containsEntry("quantity", 2);
        }
    }

    @Nested
    @DisplayName("idsOf")
    class IdsOf {
        @Test
        @DisplayName("ดึง id ออกมาตามลำดับ")
        void mapsIds() {
            OrderEntity o1 = new OrderEntity();
            o1.setId(1L);
            OrderEntity o2 = new OrderEntity();
            o2.setId(2L);
            assertThat(service.idsOf(List.of(o1, o2))).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("list ว่าง → คืน list ว่าง")
        void empty() {
            assertThat(service.idsOf(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("summary")
    class Summary {
        private final LocalDateTime from = LocalDateTime.of(2026, 5, 1, 0, 0, 0);
        private final LocalDateTime to = LocalDateTime.of(2026, 5, 31, 23, 59, 59);

        @Test
        @DisplayName("รวม KPI / นับสถานะ / ช่องทาง / ยอดขายรายวัน ครบถ้วน")
        void aggregatesEverything() {
            // revenue
            when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any(), any()))
                    .thenReturn(8000.0);
            // count แยกตาม SQL: delivered=13, valid(IN ...)=15, total=19
            when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenAnswer(inv -> {
                        String sql = inv.getArgument(0);
                        if (sql.contains("= 'delivered'")) return 13L;
                        if (sql.contains("IN ('confirmed'")) return 15L;
                        return 19L;
                    });
            // list แยกตาม SQL: รายวัน vs นับสถานะ
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenAnswer(inv -> {
                        String sql = inv.getArgument(0);
                        if (sql.contains("GROUP BY day")) {
                            return List.of(Map.<String, Object>of("day", "2026-05-30",
                                    "revenue", 5000.0, "orders", 10L));
                        }
                        return List.of(
                                Map.<String, Object>of("order_status", "delivered", "cnt", 13L),
                                Map.<String, Object>of("order_status", "pending", "cnt", 2L));
                    });
            // channel counts
            when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                    .thenReturn(Map.<String, Object>of(
                            "total", 19L, "pos", 3L, "dinein", 5L,
                            "online", 11L, "alacarte", 4L, "buffet", 1L));

            Map<String, Object> r = service.summary(from, to);

            assertThat(r.get("totalRevenue")).isEqualTo(8000.0);
            assertThat(r.get("validOrderCount")).isEqualTo(15L);
            assertThat(r.get("totalOrderCount")).isEqualTo(19L);
            assertThat(r.get("successCount")).isEqualTo(13L);
            assertThat(r.get("avgOrderValue")).isEqualTo(533L); // round(8000/15)

            @SuppressWarnings("unchecked")
            Map<String, Long> sc = (Map<String, Long>) r.get("statusCounts");
            assertThat(sc).containsEntry("delivered", 13L).containsEntry("pending", 2L);

            @SuppressWarnings("unchecked")
            Map<String, Long> cc = (Map<String, Long>) r.get("channelCounts");
            assertThat(cc)
                    .containsEntry("all", 19L)
                    .containsEntry("online", 11L)
                    .containsEntry("pos", 3L)
                    .containsEntry("dineIn", 5L)
                    .containsEntry("alacarte", 4L)
                    .containsEntry("buffet", 1L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> daily =
                    (List<Map<String, Object>>) r.get("salesDaily");
            assertThat(daily).hasSize(1);
            assertThat(daily.get(0))
                    .containsEntry("date", "2026-05-30")
                    .containsEntry("revenue", 5000.0)
                    .containsEntry("orders", 10L);
        }

        @Test
        @DisplayName("ไม่มี validOrders → avgOrderValue = 0 (ไม่หารด้วยศูนย์)")
        void zeroValidOrders() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any(), any()))
                    .thenReturn(0.0);
            when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(0L);
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                    .thenReturn(Map.<String, Object>of(
                            "total", 0L, "pos", 0L, "dinein", 0L,
                            "online", 0L, "alacarte", 0L, "buffet", 0L));

            Map<String, Object> r = service.summary(from, to);

            assertThat(r.get("avgOrderValue")).isEqualTo(0L);
            assertThat(r.get("totalRevenue")).isEqualTo(0.0);
        }
    }
}