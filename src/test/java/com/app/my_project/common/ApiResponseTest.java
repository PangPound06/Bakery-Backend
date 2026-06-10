package com.app.my_project.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test ApiResponse — helper สร้าง ResponseEntity แบบสม่ำเสมอ
 * เช็คทั้ง status code และ body (success / message / data ที่ merge เข้ามา)
 */
class ApiResponseTest {

    @Test
    @DisplayName("ok(message, data): success=true + message + data merge เข้า body")
    void okWithMessageAndData() {
        ResponseEntity<Map<String, Object>> res =
                ApiResponse.ok("สำเร็จ", Map.of("orderId", 5, "total", 100.0));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("message")).isEqualTo("สำเร็จ");
        assertThat(body.get("orderId")).isEqualTo(5);
        assertThat(body.get("total")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("ok(message): มี message แต่ไม่มี data")
    void okMessageOnly() {
        ResponseEntity<Map<String, Object>> res = ApiResponse.ok("เสร็จแล้ว");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        assertThat(res.getBody()).containsEntry("message", "เสร็จแล้ว");
    }

    @Test
    @DisplayName("ok(data): มี data แต่ไม่มี message")
    void okDataOnly() {
        ResponseEntity<Map<String, Object>> res =
                ApiResponse.ok(Map.of("count", 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        assertThat(res.getBody()).containsEntry("count", 3);
        assertThat(res.getBody()).doesNotContainKey("message");
    }

    @Test
    @DisplayName("error(status, message): success=false + status ตามที่ส่ง")
    void errorCustomStatus() {
        ResponseEntity<Map<String, Object>> res =
                ApiResponse.error(HttpStatus.CONFLICT, "ซ้ำ");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsEntry("success", false);
        assertThat(res.getBody()).containsEntry("message", "ซ้ำ");
    }

    @Test
    @DisplayName("badRequest → 400")
    void badRequest400() {
        ResponseEntity<Map<String, Object>> res = ApiResponse.badRequest("ข้อมูลผิด");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("success", false);
        assertThat(res.getBody()).containsEntry("message", "ข้อมูลผิด");
    }

    @Test
    @DisplayName("unauthorized → 401 + ข้อความ default")
    void unauthorized401() {
        ResponseEntity<Map<String, Object>> res = ApiResponse.unauthorized();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).containsEntry("success", false);
        assertThat(res.getBody().get("message")).isEqualTo("กรุณาเข้าสู่ระบบ");
    }

    @Test
    @DisplayName("forbidden → 403 + ข้อความ default")
    void forbidden403() {
        ResponseEntity<Map<String, Object>> res = ApiResponse.forbidden();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("message")).isEqualTo("ไม่มีสิทธิ์เข้าถึง");
    }

    @Test
    @DisplayName("notFound → 404 / serverError → 500")
    void notFoundAndServerError() {
        assertThat(ApiResponse.notFound("ไม่เจอ").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ApiResponse.serverError("พัง").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}