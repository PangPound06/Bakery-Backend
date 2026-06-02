package com.app.my_project.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test UploadFileController — ทดสอบ branch การ validate ไฟล์ (ก่อนเรียก Cloudinary)
 * เส้นทาง upload/destroy สำเร็จต้องต่อ Cloudinary จริง (external) จึงควรทำเป็น integration test
 */
class UploadFileControllerTest {

    private UploadFileController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadFileController();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> r) {
        return (Map<String, Object>) r.getBody();
    }

    @Test
    @DisplayName("uploadImage: ไฟล์ว่าง → 400")
    void empty() {
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);
        ResponseEntity<?> r = controller.uploadImage(file);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(body(r).get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("uploadImage: ชนิดไฟล์ไม่รองรับ → 400")
    void notAllowedType() {
        MockMultipartFile file =
                new MockMultipartFile("file", "x.pdf", "application/pdf", "data".getBytes());
        ResponseEntity<?> r = controller.uploadImage(file);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(body(r).get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("uploadImage: ไฟล์เกิน 5MB → 400")
    void tooLarge() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", big);
        ResponseEntity<?> r = controller.uploadImage(file);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }
}