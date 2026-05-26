package com.app.my_project.controller;

import com.app.my_project.dto.request.CreateReservationRequest;
import com.app.my_project.dto.request.UpdateReservationStatusRequest;
import com.app.my_project.entity.TableReservationEntity;
import com.app.my_project.repository.TableReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test TableReservationController
 *
 * Focus: business rules ที่ @Valid ทำไม่ได้
 *   - จองย้อนหลังไม่ได้
 *   - นอกเวลา 10:00-20:00 ไม่ได้
 *   - slot ต้องตรง 60 นาที
 *   - conflict detection (โต๊ะถูกจองแล้ว)
 *   - admin/user authorization
 *
 * วิธี mock SecurityContext: setAuthentication เอง ก่อน test แต่ละครั้ง
 */
@ExtendWith(MockitoExtension.class)
class TableReservationControllerTest {

    @Mock private TableReservationRepository reservationRepository;

    private TableReservationController controller;

    @BeforeEach
    void setUp() {
        controller = new TableReservationController(reservationRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void mockUser(String email) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void mockAdmin(String email) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CreateReservationRequest validRequest() {
        // ใช้พรุ่งนี้เพื่อกัน flaky test กลางคืนตอนทดสอบ
        return new CreateReservationRequest(
                "5",
                LocalDate.now().plusDays(1),
                LocalTime.of(12, 0),
                4,
                "John Doe",
                "0812345678",
                "ริมหน้าต่าง"
        );
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/reservations — createReservation()")
    class CreateReservationTests {

        @Test
        @DisplayName("ไม่ login → 401 Unauthorized")
        void createReservation_noAuth_returns401() {
            ResponseEntity<?> response = controller.createReservation(validRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("จองวันที่ผ่านไปแล้ว → 400")
        void createReservation_pastDate_returns400() {
            mockUser("user@example.com");
            CreateReservationRequest req = new CreateReservationRequest(
                    "5",
                    LocalDate.now().minusDays(1), // ✗ เมื่อวาน
                    LocalTime.of(12, 0),
                    2, "John", "0812345678", null
            );

            ResponseEntity<?> response = controller.createReservation(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertResponseMessageContains(response, "ย้อนหลัง");
        }

        @Test
        @DisplayName("เวลาก่อน 10:00 → 400")
        void createReservation_beforeOpenTime_returns400() {
            mockUser("user@example.com");
            CreateReservationRequest req = new CreateReservationRequest(
                    "5",
                    LocalDate.now().plusDays(1),
                    LocalTime.of(8, 0), // ✗ ก่อนเปิด
                    2, "John", "0812345678", null
            );

            ResponseEntity<?> response = controller.createReservation(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertResponseMessageContains(response, "10:00");
        }

        @Test
        @DisplayName("เวลาหลัง 20:00 → 400")
        void createReservation_afterCloseTime_returns400() {
            mockUser("user@example.com");
            CreateReservationRequest req = new CreateReservationRequest(
                    "5",
                    LocalDate.now().plusDays(1),
                    LocalTime.of(21, 0), // ✗ หลังปิด
                    2, "John", "0812345678", null
            );

            ResponseEntity<?> response = controller.createReservation(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("เวลา :30 (ไม่ตรง 60 นาที) → 400")
        void createReservation_invalidSlot_returns400() {
            mockUser("user@example.com");
            CreateReservationRequest req = new CreateReservationRequest(
                    "5",
                    LocalDate.now().plusDays(1),
                    LocalTime.of(12, 30), // ✗ ไม่ตรง slot
                    2, "John", "0812345678", null
            );

            ResponseEntity<?> response = controller.createReservation(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertResponseMessageContains(response, "60 นาที");
        }

        @Test
        @DisplayName("โต๊ะถูกจองในเวลานั้นแล้ว → 409 CONFLICT")
        void createReservation_conflictingTime_returns409() {
            mockUser("user@example.com");
            // mock ให้มี conflict
            when(reservationRepository.findConflicting(anyString(), any(), any()))
                    .thenReturn(List.of(new TableReservationEntity()));

            ResponseEntity<?> response = controller.createReservation(validRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertResponseMessageContains(response, "ถูกจอง");
        }

        @Test
        @DisplayName("Happy path: จองสำเร็จ → 200 + reservationCode")
        void createReservation_validRequest_returns200() {
            mockUser("user@example.com");
            when(reservationRepository.findConflicting(anyString(), any(), any()))
                    .thenReturn(List.of()); // ไม่มี conflict
            when(reservationRepository.save(any(TableReservationEntity.class)))
                    .thenAnswer(inv -> {
                        TableReservationEntity e = inv.getArgument(0);
                        e.setId(1L);
                        return e;
                    });

            ResponseEntity<?> response = controller.createReservation(validRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = castBody(response);
            assertThat(body).containsKey("reservationCode");
            assertThat((String) body.get("reservationCode")).startsWith("RES-");

            verify(reservationRepository).save(any(TableReservationEntity.class));
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/reservations/{id}/cancel — cancelReservation()")
    class CancelReservationTests {

        private TableReservationEntity makeReservation(String email, String status) {
            TableReservationEntity r = new TableReservationEntity();
            r.setId(1L);
            r.setEmail(email);
            r.setStatus(status);
            return r;
        }

        @Test
        @DisplayName("เจ้าของยกเลิกได้")
        void cancel_owner_succeeds() {
            mockUser("alice@example.com");
            when(reservationRepository.findById(1L))
                    .thenReturn(Optional.of(makeReservation("alice@example.com", "pending")));

            ResponseEntity<?> response = controller.cancelReservation(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("admin ยกเลิกของคนอื่นได้")
        void cancel_admin_canCancelOthers() {
            mockAdmin("admin@example.com");
            when(reservationRepository.findById(1L))
                    .thenReturn(Optional.of(makeReservation("alice@example.com", "pending")));

            ResponseEntity<?> response = controller.cancelReservation(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("user คนอื่น (ไม่ใช่เจ้าของ, ไม่ใช่ admin) → 403")
        void cancel_otherUser_returns403() {
            mockUser("bob@example.com"); // ไม่ใช่เจ้าของ
            when(reservationRepository.findById(1L))
                    .thenReturn(Optional.of(makeReservation("alice@example.com", "pending")));

            ResponseEntity<?> response = controller.cancelReservation(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("ยกเลิกที่ถูกยกเลิกไปแล้ว → 400")
        void cancel_alreadyCancelled_returns400() {
            mockUser("alice@example.com");
            when(reservationRepository.findById(1L))
                    .thenReturn(Optional.of(makeReservation("alice@example.com", "cancelled")));

            ResponseEntity<?> response = controller.cancelReservation(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("ยกเลิกที่ completed แล้ว → 400")
        void cancel_completed_returns400() {
            mockUser("alice@example.com");
            when(reservationRepository.findById(1L))
                    .thenReturn(Optional.of(makeReservation("alice@example.com", "completed")));

            ResponseEntity<?> response = controller.cancelReservation(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("ไม่พบ id → 404")
        void cancel_notFound_returns404() {
            mockUser("alice@example.com");
            when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.cancelReservation(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/reservations/admin/{id}/status — admin only")
    class AdminUpdateStatusTests {

        @Test
        @DisplayName("user ธรรมดา → 403")
        void updateStatus_nonAdmin_returns403() {
            mockUser("user@example.com");

            ResponseEntity<?> response = controller.updateStatus(
                    1L, new UpdateReservationStatusRequest("confirmed"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("admin update สำเร็จ")
        void updateStatus_admin_succeeds() {
            mockAdmin("admin@example.com");
            TableReservationEntity r = new TableReservationEntity();
            r.setId(1L);
            r.setStatus("pending");
            when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResponseEntity<?> response = controller.updateStatus(
                    1L, new UpdateReservationStatusRequest("confirmed"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Helpers
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    private static void assertResponseMessageContains(ResponseEntity<?> response, String text) {
        Map<String, Object> body = castBody(response);
        assertThat(body).isNotNull();
        assertThat((String) body.get("message")).contains(text);
    }
}