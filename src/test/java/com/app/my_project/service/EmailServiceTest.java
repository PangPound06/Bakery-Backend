package com.app.my_project.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test EmailService — สร้างข้อความผ่าน MimeMessage จริง แล้วยืนยันว่า
 * เรียก mailSender.send() พร้อม subject/ผู้รับที่ถูกต้อง (mock JavaMailSender)
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailService service;

    @BeforeEach
    void setUp() {
        // fromEmail ปกติฉีดจาก @Value("${spring.mail.username}") → set เองในเทส
        ReflectionTestUtils.setField(service, "fromEmail", "noreply@poundbakery.com");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    @DisplayName("sendOtpEmail: ส่งอีเมล + subject มี OTP + ผู้รับถูกต้อง")
    void sendOtpEmail() throws Exception {
        service.sendOtpEmail("user@test.com", "123456");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(cap.capture());
        MimeMessage sent = cap.getValue();

        assertThat(sent.getSubject()).contains("OTP");
        assertThat(sent.getAllRecipients()).hasSize(1);
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("user@test.com");
    }

    @Test
    @DisplayName("sendPasswordChangedEmail: ส่งอีเมล + subject เรื่องรหัสผ่าน + ผู้รับถูกต้อง")
    void sendPasswordChangedEmail() throws Exception {
        service.sendPasswordChangedEmail("user@test.com");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(cap.capture());
        MimeMessage sent = cap.getValue();

        assertThat(sent.getSubject()).contains("รหัสผ่าน");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("user@test.com");
    }
}