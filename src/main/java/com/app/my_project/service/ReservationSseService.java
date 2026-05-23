package com.app.my_project.service;

import com.app.my_project.dto.response.ReservationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE service สำหรับ broadcast event ของ Table Reservation
 * 
 * - เก็บ SseEmitter ของ admin clients ทุกคนที่ subscribe อยู่
 * - heartbeat ทุก 25 วิ กัน connection idle timeout จาก proxy
 * - cleanup emitter อัตโนมัติเมื่อ complete/timeout/error
 */
@Service
public class ReservationSseService {

    private static final Logger log = LoggerFactory.getLogger(ReservationSseService.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30 นาที

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    public ReservationSseService() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, 25, 25, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe() {
        Long id = idGen.incrementAndGet();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        emitter.onCompletion(() -> remove(id, "completion"));
        emitter.onTimeout(() -> remove(id, "timeout"));
        emitter.onError(err -> remove(id, "error: " + err.getMessage()));

        emitters.put(id, emitter);
        log.info("[SSE] client #{} connected, total={}", id, emitters.size());

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove(id, "initial send failed");
        }
        return emitter;
    }

    public void broadcast(String type, Long reservationId, ReservationResponse payload) {
        Map<String, Object> data = Map.of(
            "type", type,
            "id", reservationId,
            "reservation", payload == null ? "" : payload
        );

        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("reservation").data(data));
            } catch (IOException e) {
                log.warn("[SSE] send failed to client #{}, removing", id);
                remove(id, "send failed");
            }
        });
    }

    private void sendHeartbeat() {
        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException e) {
                remove(id, "heartbeat failed");
            }
        });
    }

    private void remove(Long id, String reason) {
        if (emitters.remove(id) != null) {
            log.info("[SSE] client #{} removed ({}), total={}", id, reason, emitters.size());
        }
    }
}