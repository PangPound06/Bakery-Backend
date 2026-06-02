package com.app.my_project.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthValidationTest {

    @Test
    @DisplayName("isValidEmailFormat: อีเมลที่ถูกต้อง → true")
    void validEmails() {
        assertThat(AuthValidation.isValidEmailFormat("admin@empbakery.com")).isTrue();
        assertThat(AuthValidation.isValidEmailFormat("user.name+tag@sub.domain.co")).isTrue();
        assertThat(AuthValidation.isValidEmailFormat("ABC@EMP.COM")).isTrue();
        assertThat(AuthValidation.isValidEmailFormat("a_b%c@x-y.io")).isTrue();
    }

    @Test
    @DisplayName("isValidEmailFormat: อีเมลผิดรูป → false")
    void invalidEmails() {
        assertThat(AuthValidation.isValidEmailFormat("plainaddress")).isFalse();
        assertThat(AuthValidation.isValidEmailFormat("@b.com")).isFalse();
        assertThat(AuthValidation.isValidEmailFormat("a@")).isFalse();
        assertThat(AuthValidation.isValidEmailFormat("a@b")).isFalse();
        assertThat(AuthValidation.isValidEmailFormat("a@b.c")).isFalse(); // tld สั้นไป
        assertThat(AuthValidation.isValidEmailFormat("a b@c.com")).isFalse(); // มีช่องว่าง
        assertThat(AuthValidation.isValidEmailFormat("")).isFalse();
    }

    @Test
    @DisplayName("isValidEmailFormat: null → false (ไม่ NPE)")
    void nullEmail() {
        assertThat(AuthValidation.isValidEmailFormat(null)).isFalse();
    }

    @Test
    @DisplayName("withinLength: ความยาวไม่เกิน max → true (รวมขอบเขตพอดี)")
    void withinLengthOk() {
        assertThat(AuthValidation.withinLength("abc", 5)).isTrue();
        assertThat(AuthValidation.withinLength("abcde", 5)).isTrue(); // เท่ากับ max พอดี
        assertThat(AuthValidation.withinLength("", 5)).isTrue();
    }

    @Test
    @DisplayName("withinLength: เกิน max → false")
    void withinLengthOver() {
        assertThat(AuthValidation.withinLength("abcdef", 5)).isFalse();
    }

    @Test
    @DisplayName("withinLength: null → false")
    void withinLengthNull() {
        assertThat(AuthValidation.withinLength(null, 5)).isFalse();
    }

    @Test
    @DisplayName("ค่าคงที่ความยาวสูงสุดเป็นไปตามที่กำหนด")
    void constants() {
        assertThat(AuthValidation.MAX_EMAIL_LEN).isEqualTo(254);
        assertThat(AuthValidation.MAX_PASSWORD_LEN).isEqualTo(100);
    }
}