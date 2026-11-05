package com.app.my_project.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * เก็บรูปบน Cloudinary — ที่เดียวที่รู้จัก Cloudinary
 *
 * ใช้ร่วมกันระหว่างรูปสินค้า (UploadFileController) และสลิปโอนเงิน (CheckoutController)
 */
@Service
public class ImageStorageService {

    /** โฟลเดอร์รูปสินค้าเดิม — เปลี่ยนไม่ได้ รูปเก่าอ้าง path นี้อยู่ */
    public static final String FOLDER_PRODUCTS = "bakery";
    /** แยกสลิปออกจากรูปสินค้า — มีข้อมูลบัญชีลูกค้า ไม่ควรปนกับรูปที่เปิดสาธารณะ */
    public static final String FOLDER_SLIPS = "bakery/slips";

    private final Cloudinary cloudinary;

    public ImageStorageService(
            @Value("${cloudinary.cloud-name}") String cloudName,
            @Value("${cloudinary.api-key}") String apiKey,
            @Value("${cloudinary.api-secret}") String apiSecret) {
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret));
    }

    /** รูปที่เก็บแล้ว — url เป็น https เสมอ */
    public record StoredImage(String url, String publicId) {}

    /** ตั้งชื่อไฟล์เป็น UUID เสมอ — ชื่อไฟล์จากผู้ใช้อาจมีภาษาไทย/อักขระพิเศษ */
    public StoredImage upload(byte[] bytes, String folder) throws IOException {
        Map<?, ?> result = cloudinary.uploader().upload(bytes, ObjectUtils.asMap(
                "folder", folder,
                "public_id", UUID.randomUUID().toString()));
        return new StoredImage((String) result.get("secure_url"), (String) result.get("public_id"));
    }

    public void delete(String publicId) throws IOException {
        cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
    }
}
