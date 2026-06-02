package com.app.my_project.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test CartController — เน้น auth-guard ของทุก endpoint (token ไม่ถูกต้อง → ปฏิเสธ)
 * และเส้นทาง validate หลังผ่าน auth (สร้าง JWT จริงด้วย HMAC + mock การ lookup email)
 *
 * หมายเหตุ: เส้นทาง CRUD ที่ลงลึก (insert/update พร้อมเช็คสต็อก) มี SQL หลายชั้น
 * เหมาะกับ integration test (H2) มากกว่า unit test ที่ต้อง mock ทั้ง chain
 */
@ExtendWith(MockitoExtension.class)
class CartControllerTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection conn;

    @InjectMocks
    private CartController controller;

    private static final String SECRET = "test-secret-key-1234567890";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "jwtSecret", SECRET);
    }

    private String validToken() {
        return JWT.create().withSubject("1").sign(Algorithm.HMAC256(SECRET));
    }

    @Test
    @DisplayName("addToCart: token ไม่ถูกต้อง → 401")
    void addToCartUnauthorized() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        ResponseEntity<?> r =
                controller.addToCart("Bearer invalid", new CartController.AddToCartRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("getCart: token ไม่ถูกต้อง → 401")
    void getCartUnauthorized() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        ResponseEntity<?> r = controller.getCart("Bearer invalid");
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("updateCart: token ไม่ถูกต้อง → 400")
    void updateCartUnauthorized() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        ResponseEntity<?> r =
                controller.updateCart("Bearer invalid", 1L, new CartController.UpdateCartRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("deleteFromCart: token ไม่ถูกต้อง → 401")
    void deleteUnauthorized() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        ResponseEntity<?> r = controller.deleteFromCart("Bearer invalid", 1L);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("clearCart: token ไม่ถูกต้อง → 401")
    void clearUnauthorized() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        ResponseEntity<?> r = controller.clearCart("Bearer invalid");
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("addToCart: ผ่าน auth แต่ไม่ส่ง productId → 400")
    void addToCartAuthorizedNullProductId() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);

        PreparedStatement emailStmt = mock(PreparedStatement.class);
        ResultSet emailRs = mock(ResultSet.class);
        when(conn.prepareStatement("SELECT email FROM tb_userregister WHERE id = ?"))
                .thenReturn(emailStmt);
        when(emailStmt.executeQuery()).thenReturn(emailRs);
        when(emailRs.next()).thenReturn(true);
        when(emailRs.getString("email")).thenReturn("user@test.com");

        // ไม่ตั้ง productId → ต้องได้ 400
        ResponseEntity<?> r =
                controller.addToCart("Bearer " + validToken(), new CartController.AddToCartRequest());

        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }
}