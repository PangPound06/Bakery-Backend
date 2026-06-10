package com.app.my_project.controller;

import com.app.my_project.common.ApiResponse;
import com.app.my_project.entity.StoreSettingEntity;
import com.app.my_project.service.JwtService;
import com.app.my_project.service.StoreSettingService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * สถานะร้าน (เปิด/ปิดรับออเดอร์ออนไลน์)
 *  - GET  /api/store/status           (public)  → ฝั่งลูกค้าอ่านมาแสดงแบนเนอร์/ปิดปุ่ม
 *  - PUT  /api/store/online-ordering  (admin)   → แอดมินกดเปิด/ปิด
 */
@RestController
@RequestMapping("/api/store")
public class StoreSettingController {

    private final StoreSettingService storeSettingService;
    private final JwtService jwtService;

    public StoreSettingController(StoreSettingService storeSettingService, JwtService jwtService) {
        this.storeSettingService = storeSettingService;
        this.jwtService = jwtService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ApiResponse.ok(toView(storeSettingService.getStatus()));
    }

    @PutMapping("/online-ordering")
    public ResponseEntity<Map<String, Object>> setOnlineOrdering(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        if (!jwtService.isAdmin(auth)) {
            return ApiResponse.forbidden();
        }

        boolean open = Boolean.TRUE.equals(body.get("open"));
        String message = body.get("message") == null ? null : body.get("message").toString();
        String reopenAt = body.get("reopenAt") == null ? null : body.get("reopenAt").toString();

        StoreSettingEntity saved = storeSettingService.setOnlineOrdering(open, message, reopenAt);
        return ApiResponse.ok(open ? "เปิดรับออเดอร์ออนไลน์แล้ว" : "ปิดรับออเดอร์ออนไลน์แล้ว",
                toView(saved));
    }

    private Map<String, Object> toView(StoreSettingEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onlineOrdering", s.isOnlineOrdering());
        m.put("message", s.getClosedMessage() == null ? "" : s.getClosedMessage());
        m.put("reopenAt", s.getReopenAt() == null ? "" : s.getReopenAt());
        return m;
    }
}