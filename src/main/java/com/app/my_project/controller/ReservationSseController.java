package com.app.my_project.controller;

import com.app.my_project.service.ReservationSseService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint สำหรับ admin subscribe การเปลี่ยนแปลงของ reservation
 * 
 * Note: รับ JWT ผ่าน query string (?token=xxx) เพราะ EventSource API
 * ของ browser ส่ง custom header ไม่ได้
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationSseController {

    private final ReservationSseService sseService;

    public ReservationSseController(ReservationSseService sseService) {
        this.sseService = sseService;
    }

    @GetMapping(value = "/admin/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object stream() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        return sseService.subscribe();
    }
}