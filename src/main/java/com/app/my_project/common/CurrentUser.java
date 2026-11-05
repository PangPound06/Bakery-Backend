package com.app.my_project.common;

import com.app.my_project.entity.AdminEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.service.JwtService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ระบุตัวตนจาก Authorization header
 *
 * ═══ ทำไมต้องเช็ค role ก่อนหา email ═══
 * ลูกค้าอยู่ tb_users ส่วนแอดมินอยู่ tb_admin — id ของสองตารางซ้ำกันได้
 * ถ้าเอา id จาก token ไปหาในตารางผิด token แอดมิน id 1 จะกลายเป็นลูกค้า id 1
 * จึงต้องดู role ใน token ก่อนเสมอว่าจะหาในตารางไหน
 */
@Component
public class CurrentUser {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AdminRepository adminRepository;

    public CurrentUser(JwtService jwtService, UserRepository userRepository, AdminRepository adminRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
    }

    /** email ของลูกค้าที่ login อยู่ — ว่างถ้าไม่มี token, token ไม่ใช่ลูกค้า หรือบัญชีถูกลบไปแล้ว */
    public Optional<String> customerEmail(String authHeader) {
        if (!jwtService.isUser(authHeader))
            return Optional.empty();
        Long id = jwtService.getUserIdFromHeader(authHeader);
        return id == null ? Optional.empty() : userRepository.findById(id).map(UserEntity::getEmail);
    }

    /** email ของแอดมินที่ยังมีตัวตนอยู่ — ว่างถ้าไม่ใช่แอดมิน หรือ token ค้างของแอดมินที่ถูกลบไปแล้ว */
    public Optional<String> adminEmail(String authHeader) {
        if (!jwtService.isAdmin(authHeader))
            return Optional.empty();
        Long id = jwtService.getUserIdFromHeader(authHeader);
        return id == null ? Optional.empty() : adminRepository.findById(id).map(AdminEntity::getEmail);
    }

    public boolean isAdmin(String authHeader) {
        return adminEmail(authHeader).isPresent();
    }

    /** แอดมิน หรือเจ้าของข้อมูล (เทียบ email แบบไม่สนตัวพิมพ์) */
    public boolean isAdminOrOwner(String authHeader, String ownerEmail) {
        if (isAdmin(authHeader))
            return true;
        return ownerEmail != null && customerEmail(authHeader).filter(ownerEmail::equalsIgnoreCase).isPresent();
    }
}
