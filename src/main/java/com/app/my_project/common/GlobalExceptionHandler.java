package com.app.my_project.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler — จัดการ error ที่เกิดจาก @Valid และ JSON parsing
 *
 * เมื่อ @Valid fail (เช่น tableNo เป็น null, partySize > 20):
 *   - Spring throw MethodArgumentNotValidException
 *   - Handler นี้แปลงเป็น response สวยๆ ส่งกลับ frontend
 *
 * Response format:
 *   {
 *     "success": false,
 *     "message": "ข้อมูลไม่ถูกต้อง",
 *     "errors": {
 *       "tableNo": "กรุณาเลือกโต๊ะ",
 *       "partySize": "จำนวนคนสูงสุด 20"
 *     }
 *   }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** จับ @Valid validation failures */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
                errors.put(err.getField(), err.getDefaultMessage())
        );

        // เอา error message ตัวแรกขึ้นเป็น top-level message (frontend ส่วนใหญ่อ่านตัวนี้)
        String firstMessage = errors.values().stream().findFirst().orElse("ข้อมูลไม่ถูกต้อง");

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", firstMessage);
        response.put("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /** จับ JSON malformed หรือ type mismatch (เช่นส่ง string ในที่ที่ต้องเป็น Integer) */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonError(HttpMessageNotReadableException ex) {
        log.warn("JSON parse error: {}", ex.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "รูปแบบข้อมูลไม่ถูกต้อง");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}