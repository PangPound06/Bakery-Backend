package com.app.my_project.dto.response;

import com.app.my_project.entity.TableReservationEntity;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Response DTO — ทดแทน toMap() helper เดิม
 *
 * ข้อดี:
 *  - Type-safe (frontend รู้ schema)
 *  - Generate OpenAPI/Swagger ได้
 *  - กัน entity leak (เผลอส่งฟิลด์ลับออกไป)
 *  - Immutable
 */
public record ReservationResponse(
        Long id,
        String email,
        String tableNo,

        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate reservationDate,

        @JsonFormat(pattern = "HH:mm")
        LocalTime reservationTime,

        Integer partySize,
        String customerName,
        String customerPhone,
        String note,
        String status,
        String reservationCode,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {
    /** แปลง entity → response DTO */
    public static ReservationResponse from(TableReservationEntity r) {
        return new ReservationResponse(
                r.getId(),
                r.getEmail(),
                r.getTableNo(),
                r.getReservationDate(),
                r.getReservationTime(),
                r.getPartySize(),
                r.getCustomerName(),
                r.getCustomerPhone(),
                r.getNote(),
                r.getStatus(),
                r.getReservationCode(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}