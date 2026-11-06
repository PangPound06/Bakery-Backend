package com.app.my_project.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CartModeTest {

    @Test
    @DisplayName("ไม่ส่ง mode = ออนไลน์ (client เดิม) / ค่าที่รู้จักไม่สนตัวพิมพ์ / ค่าแปลก = empty")
    void parse() {
        assertThat(CartMode.parse(null)).contains(CartMode.ONLINE);
        assertThat(CartMode.parse(" ")).contains(CartMode.ONLINE);
        assertThat(CartMode.parse("DineIn")).contains(CartMode.DINE_IN);
        assertThat(CartMode.parse("online")).contains(CartMode.ONLINE);
        assertThat(CartMode.parse("drive-thru")).isEmpty();
    }

    @Test
    @DisplayName("บิลหน้าร้านใช้ตะกร้าทานในร้าน ที่เหลือใช้ตะกร้าออนไลน์")
    void forOrderType() {
        assertThat(CartMode.forOrderType("dine-in")).isEqualTo(CartMode.DINE_IN);
        assertThat(CartMode.forOrderType("online")).isEqualTo(CartMode.ONLINE);
        assertThat(CartMode.forOrderType(null)).isEqualTo(CartMode.ONLINE);
    }
}
