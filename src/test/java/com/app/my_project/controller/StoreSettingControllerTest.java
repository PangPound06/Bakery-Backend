package com.app.my_project.controller;

import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.StoreSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Test StoreSettingController — GET สถานะ (public) + PUT เปิด/ปิด (admin)
 */
@ExtendWith(MockitoExtension.class)
class StoreSettingControllerTest {

    @Mock
    private StoreSettingService storeSettingService;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private StoreSettingController controller;

    private static final String AUTH = "Bearer token";

    private StoreSettingEntity entity(boolean open, String msg, String reopen) {
        StoreSettingEntity s = new StoreSettingEntity();
        s.setId(1L);
        s.setOnlineOrdering(open);
        s.setClosedMessage(msg);
        s.setReopenAt(reopen);
        return s;
    }

    @Test
    @DisplayName("GET /status: 200 + onlineOrdering/message/reopenAt (null → \"\")")
    void getStatus() {
        when(storeSettingService.getStatus()).thenReturn(entity(false, "คิวเยอะ", "14:00"));

        ResponseEntity<Map<String, Object>> res = controller.getStatus();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .containsEntry("success", true)
                .containsEntry("onlineOrdering", false)
                .containsEntry("message", "คิวเยอะ")
                .containsEntry("reopenAt", "14:00");
    }

    @Test
    @DisplayName("PUT /online-ordering: ไม่ใช่ admin → 403 และไม่เรียก service")
    void setForbiddenWhenNotAdmin() {
        when(jwtService.isAdmin(AUTH)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res =
                controller.setOnlineOrdering(Map.of("open", false), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).containsEntry("success", false);
        verify(storeSettingService, never()).setOnlineOrdering(anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("PUT /online-ordering: admin ปิดรับ → 200 + ส่ง message/reopenAt ให้ service")
    void adminClose() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(storeSettingService.setOnlineOrdering(false, "คิวเยอะ", "14:00"))
                .thenReturn(entity(false, "คิวเยอะ", "14:00"));

        ResponseEntity<Map<String, Object>> res = controller.setOnlineOrdering(
                Map.of("open", false, "message", "คิวเยอะ", "reopenAt", "14:00"), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .containsEntry("success", true)
                .containsEntry("onlineOrdering", false)
                .containsEntry("message", "คิวเยอะ");
        verify(storeSettingService).setOnlineOrdering(false, "คิวเยอะ", "14:00");
    }

    @Test
    @DisplayName("PUT /online-ordering: admin เปิดรับ (ไม่ส่ง message) → 200 + message/reopenAt ว่าง")
    void adminOpen() {
        when(jwtService.isAdmin(AUTH)).thenReturn(true);
        when(storeSettingService.setOnlineOrdering(true, null, null))
                .thenReturn(entity(true, null, null));

        ResponseEntity<Map<String, Object>> res =
                controller.setOnlineOrdering(Map.of("open", true), AUTH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .containsEntry("onlineOrdering", true)
                .containsEntry("message", "")
                .containsEntry("reopenAt", "");
    }
}