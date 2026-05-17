package com.app.my_project.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request DTO สำหรับสร้างการจอง — ใช้ Java record (immutable, ไม่ต้อง getter/setter)
 *
 * Bean Validation จะ:
 *  - @NotBlank ตรวจ string ไม่ใช่ null/empty/whitespace
 *  - @NotNull ตรวจ object ไม่ใช่ null
 *  - @Min/@Max ตรวจช่วงตัวเลข
 *  - @Pattern ตรวจ regex (เบอร์โทร)
 *  - @Size ตรวจความยาว string
 *  - @JsonFormat บอก Jackson ว่ารับ date/time format ไหน
 *
 * Frontend ส่ง JSON มาแบบเดิม:
 *   {
 *     "tableNo": "5",
 *     "reservationDate": "2025-05-20",
 *     "reservationTime": "12:30",
 *     "partySize": 4,
 *     "customerName": "John",
 *     "customerPhone": "0812345678",
 *     "note": "ริมหน้าต่าง"
 *   }
 */
public record CreateReservationRequest(
        @NotBlank(message = "กรุณาเลือกโต๊ะ")
        String tableNo,

        @NotNull(message = "กรุณาเลือกวันที่")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate reservationDate,

        @NotNull(message = "กรุณาเลือกเวลา")
        @JsonFormat(pattern = "HH:mm")
        LocalTime reservationTime,

        @NotNull(message = "กรุณากรอกจำนวนคน")
        @Min(value = 1, message = "จำนวนคนต้องอย่างน้อย 1")
        @Max(value = 20, message = "จำนวนคนสูงสุด 20")
        Integer partySize,

        @NotBlank(message = "กรุณากรอกชื่อผู้จอง")
        @Size(max = 100, message = "ชื่อต้องไม่เกิน 100 ตัวอักษร")
        String customerName,

        @NotBlank(message = "กรุณากรอกเบอร์โทรศัพท์")
        @Pattern(regexp = "^0[0-9]{8,9}$", message = "เบอร์โทรไม่ถูกต้อง (ขึ้นต้นด้วย 0 และมี 9-10 หลัก)")
        String customerPhone,

        @Size(max = 500, message = "หมายเหตุต้องไม่เกิน 500 ตัวอักษร")
        String note
) {
    /** Trim white-space ใน fields ที่ user กรอก — เรียกหลัง validate */
    public CreateReservationRequest trimmed() {
        return new CreateReservationRequest(
                tableNo != null ? tableNo.trim() : null,
                reservationDate,
                reservationTime,
                partySize,
                customerName != null ? customerName.trim() : null,
                customerPhone != null ? customerPhone.trim() : null,
                note != null ? note.trim() : ""
        );
    }
}