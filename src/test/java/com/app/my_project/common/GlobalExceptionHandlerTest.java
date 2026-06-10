package com.app.my_project.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test GlobalExceptionHandler — แปลง exception เป็น response 400 สวยๆ
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleValidation: รวม field errors + ใช้ข้อความแรกเป็น message + status 400")
    void handleValidation() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
                new FieldError("req", "tableNo", "กรุณาเลือกโต๊ะ"),
                new FieldError("req", "partySize", "จำนวนคนสูงสุด 20")
        ));

        ResponseEntity<Map<String, Object>> res = handler.handleValidation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) body.get("errors");
        assertThat(errors)
                .containsEntry("tableNo", "กรุณาเลือกโต๊ะ")
                .containsEntry("partySize", "จำนวนคนสูงสุด 20");

        // message ตัวบนสุดต้องเป็นหนึ่งใน field message (HashMap ไม่การันตีลำดับ)
        assertThat(body.get("message"))
                .isIn("กรุณาเลือกโต๊ะ", "จำนวนคนสูงสุด 20");
    }

    @Test
    @DisplayName("handleValidation: ไม่มี field error → message fallback")
    void handleValidationNoErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> res = handler.handleValidation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("message")).isEqualTo("ข้อมูลไม่ถูกต้อง");
    }

    @Test
    @DisplayName("handleJsonError: JSON พัง→ 400 + ข้อความรูปแบบไม่ถูกต้อง")
    void handleJsonError() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("Unexpected token");

        ResponseEntity<Map<String, Object>> res = handler.handleJsonError(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("success", false);
        assertThat(res.getBody()).containsEntry("message", "รูปแบบข้อมูลไม่ถูกต้อง");
    }
}