package com.app.my_project.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test TransferSlipService — ตรวจครบก่อนเก็บรูป รูปที่ไม่ผ่านต้องไม่ถูกอัปโหลด
 */
@ExtendWith(MockitoExtension.class)
class TransferSlipServiceTest {

    private static final String EMAIL = "cus@test.com";
    private static final byte[] IMAGE = { 1, 2, 3 };
    private static final String REF = "slip:abc";

    @Mock private SlipQrReader slipQrReader;
    @Mock private ImageStorageService imageStorage;
    @Mock private PaymentStateService paymentStateService;

    @InjectMocks
    private TransferSlipService service;

    private void slipReads(SlipQrReader.Verdict verdict) {
        when(slipQrReader.read(IMAGE)).thenReturn(
                new SlipQrReader.Result(verdict, verdict == SlipQrReader.Verdict.OK ? REF : null));
    }

    @Test
    @DisplayName("สลิปถูกต้อง → เก็บรูปในโฟลเดอร์สลิป แล้วแจ้งโอนด้วย reference ของ QR")
    void validSlip_storedAndSubmitted() throws IOException {
        slipReads(SlipQrReader.Verdict.OK);
        when(paymentStateService.checkSubmittable(1L, EMAIL, REF)).thenReturn(PaymentStateService.TransferResult.OK);
        when(imageStorage.upload(IMAGE, ImageStorageService.FOLDER_SLIPS))
                .thenReturn(new ImageStorageService.StoredImage("https://res.cloudinary.com/s.png", "id"));
        when(paymentStateService.submitTransfer(1L, EMAIL, "https://res.cloudinary.com/s.png", REF))
                .thenReturn(PaymentStateService.TransferResult.OK);

        assertThat(service.submit(1L, EMAIL, IMAGE, "image/png")).isEqualTo(TransferSlipService.Outcome.OK);
    }

    @Test
    @DisplayName("ไม่ใช่ JPG/PNG / ไม่มีชนิดไฟล์ / ไฟล์ว่าง / ใหญ่เกิน → INVALID_FILE โดยไม่อ่าน QR")
    void invalidFile() {
        assertThat(service.submit(1L, EMAIL, IMAGE, "image/webp")).isEqualTo(TransferSlipService.Outcome.INVALID_FILE);
        assertThat(service.submit(1L, EMAIL, IMAGE, null)).isEqualTo(TransferSlipService.Outcome.INVALID_FILE);
        assertThat(service.submit(1L, EMAIL, new byte[0], "image/png")).isEqualTo(TransferSlipService.Outcome.INVALID_FILE);
        assertThat(service.submit(1L, EMAIL, new byte[(int) TransferSlipService.MAX_BYTES + 1], "image/png"))
                .isEqualTo(TransferSlipService.Outcome.INVALID_FILE);
        verifyNoInteractions(slipQrReader, imageStorage, paymentStateService);
    }

    @Test
    @DisplayName("รูปไม่มี QR / เป็น QR จ่ายเงิน → ปฏิเสธ ไม่อัปโหลด")
    void notASlip_notStored() {
        slipReads(SlipQrReader.Verdict.NO_QR);
        assertThat(service.submit(1L, EMAIL, IMAGE, "image/jpeg")).isEqualTo(TransferSlipService.Outcome.NO_QR);

        slipReads(SlipQrReader.Verdict.PAYMENT_QR);
        assertThat(service.submit(1L, EMAIL, IMAGE, "image/jpeg")).isEqualTo(TransferSlipService.Outcome.PAYMENT_QR);

        verifyNoInteractions(imageStorage, paymentStateService);
    }

    @Test
    @DisplayName("สลิปซ้ำ / order ไม่พร้อม → ปฏิเสธก่อนอัปโหลด")
    void precheckFails_notStored() {
        slipReads(SlipQrReader.Verdict.OK);
        when(paymentStateService.checkSubmittable(1L, EMAIL, REF))
                .thenReturn(PaymentStateService.TransferResult.DUPLICATE_SLIP);

        assertThat(service.submit(1L, EMAIL, IMAGE, "image/png")).isEqualTo(TransferSlipService.Outcome.DUPLICATE_SLIP);
        verifyNoInteractions(imageStorage);
        verify(paymentStateService, never()).submitTransfer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Cloudinary ล่ม → STORAGE_FAILED และไม่เปลี่ยนสถานะ order")
    void storageFails() throws IOException {
        slipReads(SlipQrReader.Verdict.OK);
        when(paymentStateService.checkSubmittable(1L, EMAIL, REF)).thenReturn(PaymentStateService.TransferResult.OK);
        when(imageStorage.upload(any(), any())).thenThrow(new IOException("down"));

        assertThat(service.submit(1L, EMAIL, IMAGE, "image/png")).isEqualTo(TransferSlipService.Outcome.STORAGE_FAILED);
        verify(paymentStateService, never()).submitTransfer(any(), any(), any(), any());
    }
}
