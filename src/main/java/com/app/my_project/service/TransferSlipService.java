package com.app.my_project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Set;

/**
 * ลูกค้าแจ้งโอนพร้อมเพย์พร้อมสลิป
 *
 * ลำดับ: ตรวจไฟล์ → อ่าน QR บนสลิป → ตรวจ order/สลิปซ้ำ → เก็บรูป → เปลี่ยนสถานะเป็นรอตรวจสอบ
 * ทุกอย่างที่ถูกและเร็วทำก่อนอัปโหลด — รูปที่ไม่ผ่านจะไม่ถูกเก็บบน Cloudinary
 */
@Service
public class TransferSlipService {

    private static final Logger log = LoggerFactory.getLogger(TransferSlipService.class);

    /** ImageIO อ่านได้แค่ JPG/PNG — WEBP/HEIC อ่าน QR ไม่ได้ จึงไม่รับ */
    static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");
    static final long MAX_BYTES = 5L * 1024 * 1024;

    public enum Outcome {
        OK,
        NOT_FOUND,
        NOT_TRANSFER,
        /** หมดเวลา หรือแอดมินตัดสินไปแล้ว */
        WRONG_STATE,
        DUPLICATE_SLIP,
        /** ไม่มีไฟล์ / ไม่ใช่ JPG-PNG / ใหญ่เกิน */
        INVALID_FILE,
        /** เปิดรูปได้แต่ไม่มี QR */
        NO_QR,
        /** แนบ QR สำหรับจ่ายเงินมา (เช่น QR ของร้าน) แทนสลิป */
        PAYMENT_QR,
        /** เก็บรูปไม่สำเร็จ (Cloudinary ล่ม) — ลองใหม่ได้ */
        STORAGE_FAILED
    }

    private final SlipQrReader slipQrReader;
    private final ImageStorageService imageStorage;
    private final PaymentStateService paymentStateService;

    public TransferSlipService(SlipQrReader slipQrReader,
            ImageStorageService imageStorage,
            PaymentStateService paymentStateService) {
        this.slipQrReader = slipQrReader;
        this.imageStorage = imageStorage;
        this.paymentStateService = paymentStateService;
    }

    public Outcome submit(Long orderId, String email, byte[] image, String contentType) {
        boolean validType = contentType != null && ALLOWED_TYPES.contains(contentType); // Set.of ห้าม contains(null)
        if (image == null || image.length == 0 || image.length > MAX_BYTES || !validType)
            return Outcome.INVALID_FILE;

        SlipQrReader.Result slip = slipQrReader.read(image);
        if (!slip.ok()) {
            return switch (slip.verdict()) {
                case NO_QR -> Outcome.NO_QR;
                case PAYMENT_QR -> Outcome.PAYMENT_QR;
                default -> Outcome.INVALID_FILE;
            };
        }

        Outcome precheck = toOutcome(paymentStateService.checkSubmittable(orderId, email, slip.reference()));
        if (precheck != Outcome.OK)
            return precheck;

        String url;
        try {
            url = imageStorage.upload(image, ImageStorageService.FOLDER_SLIPS).url();
        } catch (IOException | RuntimeException e) {
            log.error("เก็บสลิปของ order {} ไม่สำเร็จ", orderId, e);
            return Outcome.STORAGE_FAILED;
        }
        return toOutcome(paymentStateService.submitTransfer(orderId, email, url, slip.reference()));
    }

    private static Outcome toOutcome(PaymentStateService.TransferResult result) {
        return switch (result) {
            case OK -> Outcome.OK;
            case NOT_FOUND -> Outcome.NOT_FOUND;
            case NOT_TRANSFER -> Outcome.NOT_TRANSFER;
            case WRONG_STATE -> Outcome.WRONG_STATE;
            case DUPLICATE_SLIP -> Outcome.DUPLICATE_SLIP;
        };
    }
}
