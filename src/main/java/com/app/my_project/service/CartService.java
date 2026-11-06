package com.app.my_project.service;

import com.app.my_project.common.CartMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * งานกับ tb_cart ที่ service อื่นต้องใช้ (ตาราง tb_cart ไม่มี JPA entity)
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final JdbcTemplate jdbcTemplate;

    public CartService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * ล้างตะกร้าใบเดียวของลูกค้า — อีกใบไม่ถูกแตะ
     *
     * ไม่ throw: ล้างไม่ได้ก็แค่ของค้างในตะกร้า ไม่ควรทำให้การบันทึกการจ่ายเงินล้มตาม
     * (ถ้า webhook ตอบ error Stripe จะยิงซ้ำ)
     */
    public void clear(String email, CartMode mode) {
        try {
            jdbcTemplate.update("DELETE FROM tb_cart WHERE email = ? AND cart_mode = ?", email, mode.value());
        } catch (RuntimeException e) {
            log.error("ล้างตะกร้า {} ของ {} ไม่สำเร็จ", mode.value(), email, e);
        }
    }
}
