package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.dto.request.CreateReservationRequest;
import com.app.my_project.dto.request.UpdateReservationStatusRequest;
import com.app.my_project.dto.response.ReservationResponse;
import com.app.my_project.entity.TableReservationEntity;
import com.app.my_project.repository.TableReservationRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Table Reservation API — refactored ใช้ DTO + @Valid
 *
 * เปลี่ยนแปลงจากเดิม:
 *  - ใช้ DTO (record) แทน Map<String, Object>
 *  - @Valid → Bean Validation อัตโนมัติ ลด boilerplate ~50%
 *  - ลบ helper toMap() ใช้ ReservationResponse.from() แทน
 *  - Return type ชัดเจนกว่าเดิม
 */
@RestController
@RequestMapping("/api/reservations")
public class TableReservationController {

    private static final Logger log = LoggerFactory.getLogger(TableReservationController.class);

    private static final LocalTime OPEN_TIME = LocalTime.of(10, 0);
    private static final LocalTime CLOSE_TIME = LocalTime.of(20, 0);
    private static final int SLOT_MINUTES = 30;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final TableReservationRepository reservationRepository;

    public TableReservationController(TableReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    // ─── Helper ──────────────────────────────────────────────────────
    private String getCurrentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. USER — สร้างการจอง (ใช้ DTO + @Valid)
    // ═══════════════════════════════════════════════════════════════
    @PostMapping
    public ResponseEntity<?> createReservation(@Valid @RequestBody CreateReservationRequest rawRequest) {
        String email = getCurrentEmail();
        if (email == null) return ApiResponse.unauthorized();

        // Trim whitespace
        CreateReservationRequest req = rawRequest.trimmed();

        try {
            // ── Business rule validations (ที่ @Valid ทำไม่ได้) ──

            LocalDateTime reservationDateTime = LocalDateTime.of(req.reservationDate(), req.reservationTime());
            if (reservationDateTime.isBefore(LocalDateTime.now())) {
                return ApiResponse.badRequest("ไม่สามารถจองย้อนหลังได้");
            }

            if (req.reservationTime().isBefore(OPEN_TIME) || req.reservationTime().isAfter(CLOSE_TIME)) {
                return ApiResponse.badRequest(String.format(
                        "เวลาที่รับจองอยู่ระหว่าง %s - %s",
                        OPEN_TIME.format(TIME_FMT), CLOSE_TIME.format(TIME_FMT)));
            }

            if (req.reservationTime().getMinute() % SLOT_MINUTES != 0) {
                return ApiResponse.badRequest("กรุณาเลือกเวลาเป็นทุก " + SLOT_MINUTES + " นาที");
            }

            // เช็คโต๊ะว่าง
            List<TableReservationEntity> conflicts = reservationRepository.findConflicting(
                    req.tableNo(), req.reservationDate(), req.reservationTime());
            if (!conflicts.isEmpty()) {
                return ApiResponse.error(HttpStatus.CONFLICT,
                        "โต๊ะ " + req.tableNo() + " ถูกจองในช่วงเวลานี้แล้ว กรุณาเลือกเวลาอื่น");
            }

            // ── สร้าง entity จาก DTO ──
            TableReservationEntity reservation = new TableReservationEntity();
            reservation.setEmail(email);
            reservation.setTableNo(req.tableNo());
            reservation.setReservationDate(req.reservationDate());
            reservation.setReservationTime(req.reservationTime());
            reservation.setPartySize(req.partySize());
            reservation.setCustomerName(req.customerName());
            reservation.setCustomerPhone(req.customerPhone());
            reservation.setNote(req.note());
            reservation.setStatus("pending");
            reservation.setReservationCode(generateReservationCode(req.reservationDate()));
            reservation.setCreatedAt(LocalDateTime.now());
            reservation.setUpdatedAt(LocalDateTime.now());

            TableReservationEntity saved = reservationRepository.save(reservation);

            // ── Return DTO ──
            Map<String, Object> data = new HashMap<>();
            data.put("reservationCode", saved.getReservationCode());
            data.put("reservation", ReservationResponse.from(saved));
            return ApiResponse.ok("จองโต๊ะสำเร็จ", data);

        } catch (Exception e) {
            log.error("Failed to create reservation", e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. USER — ดูการจองของตัวเอง
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/my")
    public ResponseEntity<?> getMyReservations() {
        String email = getCurrentEmail();
        if (email == null) return ApiResponse.unauthorized();

        try {
            List<ReservationResponse> list = reservationRepository
                    .findByEmailOrderByReservationDateDescReservationTimeDesc(email)
                    .stream()
                    .map(ReservationResponse::from)
                    .toList();
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            log.error("Failed to get reservations for email={}", email, e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. USER — ยกเลิกการจองของตัวเอง (หรือ admin)
    // ═══════════════════════════════════════════════════════════════
    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelReservation(@PathVariable Long id) {
        String email = getCurrentEmail();
        if (email == null) return ApiResponse.unauthorized();

        try {
            Optional<TableReservationEntity> opt = reservationRepository.findById(id);
            if (opt.isEmpty()) return ApiResponse.notFound("ไม่พบการจองนี้");

            TableReservationEntity reservation = opt.get();

            if (!reservation.getEmail().equals(email) && !isAdmin()) {
                return ApiResponse.error(HttpStatus.FORBIDDEN, "คุณไม่มีสิทธิ์ยกเลิกการจองนี้");
            }
            if ("cancelled".equals(reservation.getStatus())) {
                return ApiResponse.badRequest("การจองนี้ถูกยกเลิกแล้ว");
            }
            if ("completed".equals(reservation.getStatus())) {
                return ApiResponse.badRequest("ไม่สามารถยกเลิกการจองที่เสร็จสิ้นแล้ว");
            }

            reservation.setStatus("cancelled");
            reservation.setUpdatedAt(LocalDateTime.now());
            reservationRepository.save(reservation);

            return ApiResponse.ok("ยกเลิกการจองสำเร็จ",
                    Map.of("reservation", ReservationResponse.from(reservation)));

        } catch (Exception e) {
            log.error("Failed to cancel reservation id={}", id, e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. ADMIN — ดูการจองทั้งหมด (กรองตามวันได้)
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/admin/all")
    public ResponseEntity<?> getAllReservations(@RequestParam(required = false) String date) {
        if (!isAdmin()) return ApiResponse.forbidden();

        try {
            List<TableReservationEntity> entities;
            if (date != null && !date.isBlank()) {
                LocalDate targetDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
                entities = reservationRepository.findByReservationDateOrderByReservationTimeAsc(targetDate);
            } else {
                entities = reservationRepository.findAllByOrderByReservationDateDescReservationTimeDesc();
            }
            List<ReservationResponse> list = entities.stream().map(ReservationResponse::from).toList();
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            log.error("Failed to get all reservations date={}", date, e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. ADMIN — เปลี่ยนสถานะการจอง (ใช้ DTO + @Valid)
    // ═══════════════════════════════════════════════════════════════
    @PutMapping("/admin/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReservationStatusRequest request) {
        if (!isAdmin()) return ApiResponse.forbidden();

        try {
            Optional<TableReservationEntity> opt = reservationRepository.findById(id);
            if (opt.isEmpty()) return ApiResponse.notFound("ไม่พบการจองนี้");

            TableReservationEntity reservation = opt.get();
            reservation.setStatus(request.status());
            reservation.setUpdatedAt(LocalDateTime.now());
            reservationRepository.save(reservation);

            return ApiResponse.ok("อัปเดตสถานะสำเร็จ",
                    Map.of("reservation", ReservationResponse.from(reservation)));

        } catch (Exception e) {
            log.error("Failed to update reservation status id={}", id, e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. PUBLIC — ดูช่วงเวลาว่างของโต๊ะ
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/availability")
    public ResponseEntity<?> getAvailability(@RequestParam String tableNo,
                                             @RequestParam String date) {
        try {
            LocalDate targetDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);

            List<TableReservationEntity> booked = reservationRepository
                    .findByTableNoAndReservationDateOrderByReservationTimeAsc(tableNo, targetDate);

            Set<LocalTime> bookedTimes = new HashSet<>();
            for (TableReservationEntity r : booked) {
                if (!"cancelled".equals(r.getStatus())) {
                    bookedTimes.add(r.getReservationTime());
                }
            }

            List<Map<String, Object>> slots = new ArrayList<>();
            LocalTime current = OPEN_TIME;
            while (!current.isAfter(CLOSE_TIME)) {
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("time", current.format(TIME_FMT));
                slot.put("available", !bookedTimes.contains(current));
                slots.add(slot);
                current = current.plusMinutes(SLOT_MINUTES);
            }

            return ResponseEntity.ok(Map.of(
                    "tableNo", tableNo,
                    "date", date,
                    "slots", slots));

        } catch (DateTimeParseException e) {
            return ApiResponse.badRequest("รูปแบบวันที่ไม่ถูกต้อง (yyyy-MM-dd)");
        } catch (Exception e) {
            log.error("Failed to get availability tableNo={} date={}", tableNo, date, e);
            return ApiResponse.serverError("เกิดข้อผิดพลาด: " + e.getMessage());
        }
    }

    // ─── Private helpers ───────────────────────────────────────────
    private String generateReservationCode(LocalDate date) {
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = String.format("%04d", new Random().nextInt(10000));
        return "RES-" + dateStr + "-" + random;
    }
}