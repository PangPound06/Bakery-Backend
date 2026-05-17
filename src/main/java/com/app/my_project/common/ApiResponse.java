package com.app.my_project.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper สำหรับสร้าง response แบบสม่ำเสมอ — ลด boilerplate
 * แทนการเขียน Map.of(...) หรือ new HashMap<>() ซ้ำๆ
 */
public class ApiResponse {

    private ApiResponse() {}

    /** สำเร็จ พร้อม message + data */
    public static ResponseEntity<Map<String, Object>> ok(String message, Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        if (message != null) response.put("message", message);
        if (data != null) response.putAll(data);
        return ResponseEntity.ok(response);
    }

    /** สำเร็จ พร้อม message อย่างเดียว */
    public static ResponseEntity<Map<String, Object>> ok(String message) {
        return ok(message, null);
    }

    /** สำเร็จ พร้อม data อย่างเดียว */
    public static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ok(null, data);
    }

    /** Error response */
    public static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    public static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseEntity<Map<String, Object>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบ");
    }

    public static ResponseEntity<Map<String, Object>> forbidden() {
        return error(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึง");
    }

    public static ResponseEntity<Map<String, Object>> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, message);
    }

    public static ResponseEntity<Map<String, Object>> serverError(String message) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
