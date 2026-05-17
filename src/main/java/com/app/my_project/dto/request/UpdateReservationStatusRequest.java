package com.app.my_project.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO สำหรับ admin เปลี่ยนสถานะการจอง
 *
 * Frontend ส่ง JSON:
 *   { "status": "confirmed" }
 */
public record UpdateReservationStatusRequest(
        @NotBlank(message = "กรุณาระบุสถานะ")
        @Pattern(
                regexp = "^(pending|confirmed|cancelled|completed)$",
                message = "สถานะไม่ถูกต้อง ต้องเป็น: pending, confirmed, cancelled, completed"
        )
        String status
) {}