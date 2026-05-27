package com.app.my_project.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test OrderCodeGenerator — pure logic
 */
class OrderCodeGeneratorTest {

    private final OrderCodeGenerator generator = new OrderCodeGenerator();

    @Nested
    @DisplayName("generate()")
    class GenerateTests {

        @Test
        @DisplayName("id=1 → ORD104729 + 1 = 'ORD1047291'")
        void id1() {
            assertThat(generator.generate(1L)).isEqualTo("ORD1047291");
        }

        @Test
        @DisplayName("id=10 → ORD047290 + 10 = 'ORD04729010'")
        void id10() {
            // 10 * 104729 % 1000000 = 1047290 % 1000000 = 47290
            assertThat(generator.generate(10L)).isEqualTo("ORD04729010");
        }

        @Test
        @DisplayName("id=100 → format ถูกต้อง")
        void id100() {
            String code = generator.generate(100L);
            assertThat(code).startsWith("ORD");
            assertThat(code).endsWith("100");
            // ตัวกลาง 6 หลัก
            assertThat(code.substring(3, 9)).hasSize(6);
        }

        @Test
        @DisplayName("id=null → throw IllegalArgumentException")
        void nullId_throws() {
            assertThatThrownBy(() -> generator.generate(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ทุก id ที่ generate ต้องขึ้นต้น 'ORD' + 6 digit hash + id")
        void formatConsistency() {
            for (long id = 1; id <= 100; id++) {
                String code = generator.generate(id);
                assertThat(code).matches("ORD\\d{6,}");
                assertThat(code).endsWith(String.valueOf(id));
            }
        }
    }

    @Nested
    @DisplayName("extractOrderId()")
    class ExtractTests {

        @Test
        @DisplayName("✅ Round-trip: extract(generate(id)) = id")
        void roundTrip() {
            for (long id : new long[]{1L, 5L, 42L, 100L, 999L, 12345L}) {
                String code = generator.generate(id);
                Long extracted = generator.extractOrderId(code);
                assertThat(extracted).as("Round-trip for id=%d", id).isEqualTo(id);
            }
        }

        @Test
        @DisplayName("null → null")
        void nullCode_returnsNull() {
            assertThat(generator.extractOrderId(null)).isNull();
        }

        @Test
        @DisplayName("format ผิด (ไม่ขึ้นต้น ORD) → null")
        void invalidPrefix_returnsNull() {
            assertThat(generator.extractOrderId("XYZ123456789")).isNull();
        }

        @Test
        @DisplayName("ไม่มี id ต่อท้าย (เหลือแค่ ORD + hash) → null")
        void tooShort_returnsNull() {
            assertThat(generator.extractOrderId("ORD123456")).isNull();
        }

        @Test
        @DisplayName("hash ผิด (mismatched) → null")
        void wrongHash_returnsNull() {
            // ORD999999 + 1 = 'ORD9999991' แต่ id=1 จะ generate ได้ ORD1047291
            assertThat(generator.extractOrderId("ORD9999991")).isNull();
        }

        @Test
        @DisplayName("id ส่วนท้ายไม่ใช่ตัวเลข → null")
        void nonNumericId_returnsNull() {
            assertThat(generator.extractOrderId("ORDxxxxxxABC")).isNull();
        }

        @Test
        @DisplayName("lowercase code → ก็ extract ได้ (case-insensitive)")
        void lowercaseCode_works() {
            String code = generator.generate(5L).toLowerCase();
            assertThat(generator.extractOrderId(code)).isEqualTo(5L);
        }

        @Test
        @DisplayName("Code มี whitespace → trim ออกแล้ว extract ได้")
        void withWhitespace_trimsAndWorks() {
            String code = "  " + generator.generate(7L) + "  ";
            assertThat(generator.extractOrderId(code)).isEqualTo(7L);
        }
    }
}