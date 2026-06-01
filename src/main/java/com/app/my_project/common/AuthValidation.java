package com.app.my_project.common;

import java.util.regex.Pattern;

/**
 * ตรวจรูปแบบ/ความยาวของ input สำหรับการเข้าสู่ระบบ
 * (กัน payload ยาวผิดปกติ + รูปแบบอีเมลที่ไม่ถูกต้อง — เสริมจากการใช้ parameterized query)
 */
public final class AuthValidation {

    private AuthValidation() {
    }

    public static final int MAX_EMAIL_LEN = 254;
    public static final int MAX_PASSWORD_LEN = 100;

    // รูปแบบอีเมลแบบพื้นฐาน (ไม่เข้มงวดระดับ RFC แต่กันค่าที่ผิดรูปชัดๆ)
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public static boolean isValidEmailFormat(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean withinLength(String value, int max) {
        return value != null && value.length() <= max;
    }
}