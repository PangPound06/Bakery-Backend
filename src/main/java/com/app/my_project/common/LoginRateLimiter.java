package com.app.my_project.common;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * จำกัดจำนวนครั้งที่ล็อกอินล้มเหลวต่อ key (ปกติคือ client IP) ภายในช่วงเวลาหนึ่ง
 * เพื่อชะลอการ brute-force รหัสผ่าน
 *
 * หมายเหตุ: เก็บใน memory ของแต่ละ instance — รีสตาร์ทแล้วรีเซ็ต และถ้ารันหลาย instance
 * ตัวนับจะไม่ถูกแชร์กัน (ถ้าต้องการ distributed ให้ย้ายไปใช้ Redis)
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 8; // ครั้งที่ล้มเหลวต่อ window
    private static final long WINDOW_MS = 15 * 60_000L; // 15 นาที
    private static final int MAX_KEYS = 10_000; // กัน map โตไม่จำกัด

    private static final class Counter {
        int failures;
        long windowStart;
    }

    private final Map<String, Counter> byKey = new ConcurrentHashMap<>();

    public synchronized boolean isBlocked(String key) {
        Counter c = byKey.get(key);
        if (c == null)
            return false;
        if (expired(c)) {
            byKey.remove(key);
            return false;
        }
        return c.failures >= MAX_FAILURES;
    }

    public synchronized void recordFailure(String key) {
        long now = System.currentTimeMillis();
        Counter c = byKey.get(key);
        if (c == null || expired(c)) {
            if (byKey.size() >= MAX_KEYS)
                purgeExpired();
            c = new Counter();
            c.windowStart = now;
            byKey.put(key, c);
        }
        c.failures++;
    }

    public synchronized void reset(String key) {
        byKey.remove(key);
    }

    public synchronized long retryAfterSeconds(String key) {
        Counter c = byKey.get(key);
        if (c == null)
            return 0;
        long remain = WINDOW_MS - (System.currentTimeMillis() - c.windowStart);
        return remain > 0 ? remain / 1000 : 0;
    }

    private boolean expired(Counter c) {
        return System.currentTimeMillis() - c.windowStart > WINDOW_MS;
    }

    private void purgeExpired() {
        Iterator<Map.Entry<String, Counter>> it = byKey.entrySet().iterator();
        while (it.hasNext()) {
            if (expired(it.next().getValue()))
                it.remove();
        }
    }
}