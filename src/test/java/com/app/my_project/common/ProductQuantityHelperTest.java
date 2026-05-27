package com.app.my_project.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test ProductQuantityHelper — pure logic, no mocks needed
 */
class ProductQuantityHelperTest {

    private final ProductQuantityHelper helper = new ProductQuantityHelper();

    @Nested
    @DisplayName("getMultiplier()")
    class GetMultiplierTests {

        @Test
        @DisplayName("null option → 1")
        void nullOption_returns1() {
            assertThat(helper.getMultiplier(null)).isEqualTo(1);
        }

        @Test
        @DisplayName("'1 ปอนด์ (8 ชิ้น)' → 8")
        void onePound_returns8() {
            assertThat(helper.getMultiplier("1 ปอนด์ (8 ชิ้น)")).isEqualTo(8);
        }

        @Test
        @DisplayName("'2 ปอนด์ (16 ชิ้น)' → 16")
        void twoPound_returns16() {
            assertThat(helper.getMultiplier("2 ปอนด์ (16 ชิ้น)")).isEqualTo(16);
        }

        @Test
        @DisplayName("option ที่ไม่รู้จัก (เช่น 'ขนาดเล็ก') → 1")
        void unknownOption_returns1() {
            assertThat(helper.getMultiplier("ขนาดเล็ก")).isEqualTo(1);
        }

        @Test
        @DisplayName("✅ 2 ปอนด์ ต้อง match ก่อน 1 ปอนด์ (เพราะ '2 ปอนด์' contains '1 ปอนด์' ก็ได้ถ้าเป็น substring)")
        void twoPoundBeforeOne() {
            // string "2 ปอนด์" ไม่ contains "1 ปอนด์" — กัน regression
            assertThat(helper.getMultiplier("2 ปอนด์")).isEqualTo(16);
        }
    }

    @Nested
    @DisplayName("toDisplayQty()")
    class ToDisplayQtyTests {

        @Test
        @DisplayName("16 ชิ้น + 2 ปอนด์ → 1 ออเดอร์")
        void twoPound_16to1() {
            assertThat(helper.toDisplayQty(16, "2 ปอนด์")).isEqualTo(1);
        }

        @Test
        @DisplayName("32 ชิ้น + 2 ปอนด์ → 2 ออเดอร์")
        void twoPound_32to2() {
            assertThat(helper.toDisplayQty(32, "2 ปอนด์")).isEqualTo(2);
        }

        @Test
        @DisplayName("8 ชิ้น + 1 ปอนด์ → 1 ออเดอร์")
        void onePound_8to1() {
            assertThat(helper.toDisplayQty(8, "1 ปอนด์")).isEqualTo(1);
        }

        @Test
        @DisplayName("5 ชิ้น + null option → 5 (สินค้าทั่วไป)")
        void regularProduct_passthrough() {
            assertThat(helper.toDisplayQty(5, null)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("toRawQty()")
    class ToRawQtyTests {

        @Test
        @DisplayName("1 ออเดอร์ + 2 ปอนด์ → 16 ชิ้น")
        void twoPound_1to16() {
            assertThat(helper.toRawQty(1, "2 ปอนด์")).isEqualTo(16);
        }

        @Test
        @DisplayName("3 ออเดอร์ + 1 ปอนด์ → 24 ชิ้น")
        void onePound_3to24() {
            assertThat(helper.toRawQty(3, "1 ปอนด์")).isEqualTo(24);
        }

        @Test
        @DisplayName("5 ออเดอร์ + null option → 5 (สินค้าทั่วไป)")
        void regularProduct_passthrough() {
            assertThat(helper.toRawQty(5, null)).isEqualTo(5);
        }

        @Test
        @DisplayName("✅ Round-trip: toDisplay(toRaw(x)) = x")
        void roundTrip_preservesValue() {
            for (int displayQty = 1; displayQty <= 10; displayQty++) {
                int raw = helper.toRawQty(displayQty, "2 ปอนด์");
                int backToDisplay = helper.toDisplayQty(raw, "2 ปอนด์");
                assertThat(backToDisplay).as("Round-trip for %d", displayQty)
                        .isEqualTo(displayQty);
            }
        }
    }
}