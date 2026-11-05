package com.app.my_project.common;

import com.app.my_project.entity.AdminEntity;
import com.app.my_project.entity.UserEntity;
import com.app.my_project.repository.AdminRepository;
import com.app.my_project.repository.UserRepository;
import com.app.my_project.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test CurrentUser — หา email จาก token โดยดู role ก่อนเสมอ
 * (tb_users กับ tb_admin มี id ซ้ำกันได้)
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserTest {

    private static final String ADMIN = "Bearer admin";
    private static final String USER = "Bearer user";

    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;
    @Mock private AdminRepository adminRepository;

    @InjectMocks
    private CurrentUser currentUser;

    private void customerToken(long id, String email) {
        when(jwtService.isUser(USER)).thenReturn(true);
        when(jwtService.getUserIdFromHeader(USER)).thenReturn(id);
        UserEntity user = new UserEntity();
        user.setEmail(email);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
    }

    private void adminToken(long id, String email) {
        when(jwtService.isAdmin(ADMIN)).thenReturn(true);
        when(jwtService.getUserIdFromHeader(ADMIN)).thenReturn(id);
        AdminEntity admin = new AdminEntity();
        admin.setEmail(email);
        when(adminRepository.findById(id)).thenReturn(Optional.of(admin));
    }

    @Test
    @DisplayName("token ลูกค้า → email จาก tb_users")
    void customerEmail() {
        customerToken(1L, "cus@test.com");

        assertThat(currentUser.customerEmail(USER)).contains("cus@test.com");
    }

    @Test
    @DisplayName("token แอดมิน id 1 ไม่ถูกตีเป็นลูกค้า id 1")
    void adminTokenIsNotCustomer() {
        when(jwtService.isUser(ADMIN)).thenReturn(false);

        assertThat(currentUser.customerEmail(ADMIN)).isEmpty();
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("token แอดมินที่ยังมีตัวตน → email จาก tb_admin")
    void adminEmail() {
        adminToken(1L, "boss@empbakery.com");

        assertThat(currentUser.adminEmail(ADMIN)).contains("boss@empbakery.com");
        assertThat(currentUser.isAdmin(ADMIN)).isTrue();
    }

    @Test
    @DisplayName("token ค้างของแอดมินที่ถูกลบไปแล้ว → ไม่ใช่แอดมิน")
    void deletedAdmin() {
        when(jwtService.isAdmin(ADMIN)).thenReturn(true);
        when(jwtService.getUserIdFromHeader(ADMIN)).thenReturn(99L);
        when(adminRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(currentUser.isAdmin(ADMIN)).isFalse();
    }

    @Test
    @DisplayName("ไม่มี token → ไม่ใช่ทั้งลูกค้าและแอดมิน")
    void noToken() {
        assertThat(currentUser.customerEmail(null)).isEmpty();
        assertThat(currentUser.isAdmin(null)).isFalse();
        assertThat(currentUser.isAdminOrOwner(null, "cus@test.com")).isFalse();
    }

    @Test
    @DisplayName("เจ้าของข้อมูล (ไม่สนตัวพิมพ์) → true / ลูกค้าคนอื่น → false")
    void ownerCheck() {
        customerToken(1L, "Cus@Test.com");

        assertThat(currentUser.isAdminOrOwner(USER, "cus@test.com")).isTrue();
        assertThat(currentUser.isAdminOrOwner(USER, "other@test.com")).isFalse();
        assertThat(currentUser.isAdminOrOwner(USER, null)).isFalse();
    }

    @Test
    @DisplayName("แอดมินดูข้อมูลของลูกค้าทุกคนได้")
    void adminSeesEveryone() {
        adminToken(1L, "boss@empbakery.com");

        assertThat(currentUser.isAdminOrOwner(ADMIN, "anyone@test.com")).isTrue();
    }
}
