package com.app.my_project.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private LoginRateLimiter limiter;

    // จำนวนครั้งที่ทำให้ถูกบล็อก (ตรงกับ MAX_FAILURES ในคลาส)
    private static final int MAX_FAILURES = 8;

    @BeforeEach
    void setUp() {
        limiter = new LoginRateLimiter();
    }

    @Test
    @DisplayName("ยังไม่เคยล้มเหลว → ไม่ถูกบล็อก")
    void notBlockedInitially() {
        assertThat(limiter.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("ล้มเหลวน้อยกว่าเกณฑ์ → ยังไม่ถูกบล็อก")
    void notBlockedBelowThreshold() {
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            limiter.recordFailure("1.1.1.1");
        }
        assertThat(limiter.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("ล้มเหลวครบเกณฑ์ → ถูกบล็อก")
    void blockedAtThreshold() {
        for (int i = 0; i < MAX_FAILURES; i++) {
            limiter.recordFailure("1.1.1.1");
        }
        assertThat(limiter.isBlocked("1.1.1.1")).isTrue();
    }

    @Test
    @DisplayName("reset แล้วตัวนับเคลียร์ → กลับมาไม่ถูกบล็อก")
    void resetClearsCounter() {
        for (int i = 0; i < MAX_FAILURES; i++) {
            limiter.recordFailure("1.1.1.1");
        }
        assertThat(limiter.isBlocked("1.1.1.1")).isTrue();

        limiter.reset("1.1.1.1");

        assertThat(limiter.isBlocked("1.1.1.1")).isFalse();
        assertThat(limiter.retryAfterSeconds("1.1.1.1")).isZero();
    }

    @Test
    @DisplayName("แต่ละ key (IP) นับแยกกัน")
    void keysAreIndependent() {
        for (int i = 0; i < MAX_FAILURES; i++) {
            limiter.recordFailure("1.1.1.1");
        }
        assertThat(limiter.isBlocked("1.1.1.1")).isTrue();
        assertThat(limiter.isBlocked("2.2.2.2")).isFalse();
    }

    @Test
    @DisplayName("retryAfterSeconds: ยังไม่เคยล้มเหลว → 0")
    void retryAfterSecondsZeroWhenNoFailures() {
        assertThat(limiter.retryAfterSeconds("1.1.1.1")).isZero();
    }

    @Test
    @DisplayName("retryAfterSeconds: หลังล้มเหลว → อยู่ในช่วง 0 ถึง 15 นาที")
    void retryAfterSecondsWithinWindow() {
        limiter.recordFailure("1.1.1.1");
        long secs = limiter.retryAfterSeconds("1.1.1.1");
        assertThat(secs).isGreaterThan(0).isLessThanOrEqualTo(15 * 60);
    }
}