package com.app.my_project.controller;

import com.app.my_project.entity.TableReservationEntity;
import com.app.my_project.repository.TableReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

@RestController
@RequestMapping("/api/reservations")
@CrossOrigin(origins = { "http://localhost:3000", "https://poundbakery.vercel.app" })
public class TableReservationController {

    @Autowired
    private TableReservationRepository reservationRepository;

    // ─── ช่วงเวลาที่รับจอง (ปรับตามร้าน) ───────────────────────────────────
    private static final LocalTime OPEN_TIME  = LocalTime.of(9, 0);
    private static final LocalTime CLOSE_TIME = LocalTime.of(21, 0);
    private static final int SLOT_MINUTES     = 30; // ทุก 30 นาที

    // ─── สถานะที่ admin เปลี่ยนได้ ──────────────────────────────────────────
    private static final Set<String> VALID_STATUSES =
            Set.of("pending", "confirmed", "cancelled", "completed");

    // =========================================================
    // 1. USER — สร้างการจอง
    // =========================================================
    @PostMapping
    public ResponseEntity<?> createReservation(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();

            // ── Validate input ──────────────────────────────
            String tableNo       = (String) request.get("tableNo");
            String dateStr       = (String) request.get("reservationDate"); // "2025-05-20"
            String timeStr       = (String) request.get("reservationTime"); // "12:30"
            String customerName  = (String) request.get("customerName");
            String customerPhone = (String) request.get("customerPhone");
            Object partySizeObj  = request.get("partySize");
            String note          = (String) request.getOrDefault("note", "");

            if (tableNo == null || dateStr == null || timeStr == null
                    || customerName == null || customerPhone == null || partySizeObj == null) {
                response.put("message", "กรุณากรอกข้อมูลให้ครบถ้วน (tableNo, reservationDate, reservationTime, customerName, customerPhone, partySize)");
                return ResponseEntity.badRequest().body(response);
            }

            // ── Parse date & time ───────────────────────────
            LocalDate reservationDate;
            LocalTime reservationTime;
            try {
                reservationDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                reservationTime = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (DateTimeParseException e) {
                response.put("message", "รูปแบบวันที่หรือเวลาไม่ถูกต้อง (date: yyyy-MM-dd, time: HH:mm)");
                return ResponseEntity.badRequest().body(response);
            }

            // ── ตรวจว่าไม่จองย้อนหลัง ──────────────────────
            LocalDateTime reservationDateTime = LocalDateTime.of(reservationDate, reservationTime);
            if (reservationDateTime.isBefore(LocalDateTime.now())) {
                response.put("message", "ไม่สามารถจองย้อนหลังได้");
                return ResponseEntity.badRequest().body(response);
            }

            // ── ตรวจเวลาเปิด-ปิด ────────────────────────────
            if (reservationTime.isBefore(OPEN_TIME) || reservationTime.isAfter(CLOSE_TIME)) {
                response.put("message", String.format(
                    "เวลาที่รับจองอยู่ระหว่าง %s - %s",
                    OPEN_TIME.format(DateTimeFormatter.ofPattern("HH:mm")),
                    CLOSE_TIME.format(DateTimeFormatter.ofPattern("HH:mm"))
                ));
                return ResponseEntity.badRequest().body(response);
            }

            // ── ตรวจว่าเวลาอยู่ใน slot ที่กำหนด ────────────
            if (reservationTime.getMinute() % SLOT_MINUTES != 0) {
                response.put("message", "กรุณาเลือกเวลาเป็นทุก " + SLOT_MINUTES + " นาที");
                return ResponseEntity.badRequest().body(response);
            }

            // ── ตรวจว่าโต๊ะว่างหรือไม่ ──────────────────────
            List<TableReservationEntity> conflicts =
                reservationRepository.findConflicting(tableNo, reservationDate, reservationTime);
            if (!conflicts.isEmpty()) {
                response.put("message", "โต๊ะ " + tableNo + " ถูกจองในช่วงเวลานี้แล้ว กรุณาเลือกเวลาอื่น");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }

            // ── สร้าง reservation code ───────────────────────
            String code = generateReservationCode(reservationDate);

            int partySize = (partySizeObj instanceof Integer)
                ? (Integer) partySizeObj
                : Integer.parseInt(partySizeObj.toString());

            // ── บันทึกลง DB ─────────────────────────────────
            TableReservationEntity reservation = new TableReservationEntity();
            reservation.setEmail(email);
            reservation.setTableNo(tableNo);
            reservation.setReservationDate(reservationDate);
            reservation.setReservationTime(reservationTime);
            reservation.setPartySize(partySize);
            reservation.setCustomerName(customerName.trim());
            reservation.setCustomerPhone(customerPhone.trim());
            reservation.setNote(note);
            reservation.setStatus("pending");
            reservation.setReservationCode(code);
            reservation.setCreatedAt(LocalDateTime.now());
            reservation.setUpdatedAt(LocalDateTime.now());

            TableReservationEntity saved = reservationRepository.save(reservation);

            response.put("message", "จองโต๊ะสำเร็จ");
            response.put("reservationCode", saved.getReservationCode());
            response.put("reservation", toMap(saved));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // =========================================================
    // 2. USER — ดูการจองของตัวเอง
    // =========================================================
    @GetMapping("/my")
    public ResponseEntity<?> getMyReservations() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            List<TableReservationEntity> list =
                reservationRepository.findByEmailOrderByReservationDateDescReservationTimeDesc(email);
            return ResponseEntity.ok(list.stream().map(this::toMap).toList());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    // =========================================================
    // 3. USER — ยกเลิกการจองของตัวเอง
    // =========================================================
    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelReservation(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();

            Optional<TableReservationEntity> opt = reservationRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("message", "ไม่พบการจองนี้");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            TableReservationEntity reservation = opt.get();

            // ตรวจว่าเป็นเจ้าของ
            if (!reservation.getEmail().equals(email)) {
                response.put("message", "คุณไม่มีสิทธิ์ยกเลิกการจองนี้");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            if ("cancelled".equals(reservation.getStatus())) {
                response.put("message", "การจองนี้ถูกยกเลิกแล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            if ("completed".equals(reservation.getStatus())) {
                response.put("message", "ไม่สามารถยกเลิกการจองที่เสร็จสิ้นแล้ว");
                return ResponseEntity.badRequest().body(response);
            }

            reservation.setStatus("cancelled");
            reservation.setUpdatedAt(LocalDateTime.now());
            reservationRepository.save(reservation);

            response.put("message", "ยกเลิกการจองสำเร็จ");
            response.put("reservation", toMap(reservation));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // =========================================================
    // 4. ADMIN — ดูการจองทั้งหมด (กรองตามวันได้)
    // =========================================================
    @GetMapping("/admin/all")
    public ResponseEntity<?> getAllReservations(
            @RequestParam(required = false) String date) {
        try {
            List<TableReservationEntity> list;
            if (date != null && !date.isBlank()) {
                LocalDate targetDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
                list = reservationRepository.findByReservationDateOrderByReservationTimeAsc(targetDate);
            } else {
                list = reservationRepository.findAllByOrderByReservationDateDescReservationTimeDesc();
            }
            return ResponseEntity.ok(list.stream().map(this::toMap).toList());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    // =========================================================
    // 5. ADMIN — เปลี่ยนสถานะการจอง
    // =========================================================
    @PutMapping("/admin/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String newStatus = request.get("status");
            if (newStatus == null || !VALID_STATUSES.contains(newStatus)) {
                response.put("message", "สถานะไม่ถูกต้อง ต้องเป็น: " + VALID_STATUSES);
                return ResponseEntity.badRequest().body(response);
            }

            Optional<TableReservationEntity> opt = reservationRepository.findById(id);
            if (opt.isEmpty()) {
                response.put("message", "ไม่พบการจองนี้");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            TableReservationEntity reservation = opt.get();
            reservation.setStatus(newStatus);
            reservation.setUpdatedAt(LocalDateTime.now());
            reservationRepository.save(reservation);

            response.put("message", "อัปเดตสถานะสำเร็จ");
            response.put("reservation", toMap(reservation));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("message", "เกิดข้อผิดพลาด: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // =========================================================
    // 6. PUBLIC — ดูช่วงเวลาว่างของโต๊ะ (ไม่ต้อง login)
    // =========================================================
    @GetMapping("/availability")
    public ResponseEntity<?> getAvailability(
            @RequestParam String tableNo,
            @RequestParam String date) {
        try {
            LocalDate targetDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);

            // ดึงการจองที่ active ของโต๊ะนั้นในวันนั้น
            List<TableReservationEntity> booked =
                reservationRepository.findByTableNoAndReservationDateOrderByReservationTimeAsc(
                    tableNo, targetDate);
            Set<LocalTime> bookedTimes = new HashSet<>();
            for (TableReservationEntity r : booked) {
                if (!"cancelled".equals(r.getStatus())) {
                    bookedTimes.add(r.getReservationTime());
                }
            }

            // สร้าง list ของ slot ทั้งหมด พร้อม available flag
            List<Map<String, Object>> slots = new ArrayList<>();
            LocalTime current = OPEN_TIME;
            while (!current.isAfter(CLOSE_TIME)) {
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("time", current.format(DateTimeFormatter.ofPattern("HH:mm")));
                slot.put("available", !bookedTimes.contains(current));
                slots.add(slot);
                current = current.plusMinutes(SLOT_MINUTES);
            }

            return ResponseEntity.ok(Map.of(
                "tableNo", tableNo,
                "date", date,
                "slots", slots
            ));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "รูปแบบวันที่ไม่ถูกต้อง (yyyy-MM-dd)"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "เกิดข้อผิดพลาด: " + e.getMessage()));
        }
    }

    // ─── Helper: สร้างรหัสการจอง ─────────────────────────────────────────────
    private String generateReservationCode(LocalDate date) {
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = String.format("%04d", new Random().nextInt(10000));
        return "RES-" + dateStr + "-" + random;
    }

    // ─── Helper: แปลง entity → Map (ป้องกัน circular ref) ──────────────────
    private Map<String, Object> toMap(TableReservationEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("email", r.getEmail());
        m.put("tableNo", r.getTableNo());
        m.put("reservationDate", r.getReservationDate() != null
                ? r.getReservationDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);
        m.put("reservationTime", r.getReservationTime() != null
                ? r.getReservationTime().format(DateTimeFormatter.ofPattern("HH:mm")) : null);
        m.put("partySize", r.getPartySize());
        m.put("customerName", r.getCustomerName());
        m.put("customerPhone", r.getCustomerPhone());
        m.put("note", r.getNote());
        m.put("status", r.getStatus());
        m.put("reservationCode", r.getReservationCode());
        m.put("createdAt", r.getCreatedAt() != null
                ? r.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        m.put("updatedAt", r.getUpdatedAt() != null
                ? r.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        return m;
    }
}