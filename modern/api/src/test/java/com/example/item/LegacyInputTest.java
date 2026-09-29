package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PHP 7.4 입력 처리 함수와 같은 결과가 나오는지. */
class LegacyInputTest {

    @Test
    @DisplayName("trim: PHP 가 지우는 문자만 지우고 전각 공백 · 다른 제어 문자는 남긴다")
    void trimsOnlyPhpWhitespace() {
        assertThat(LegacyInput.trim(" \t\n\r\0\u000Bab\u000B ")).isEqualTo("ab");
        assertThat(LegacyInput.trim("　ab\u0001")).isEqualTo("　ab\u0001");
    }

    @Test
    @DisplayName("intval: 숫자 앞부분만 읽고, 소수는 버리고, 범위를 넘으면 고정한다")
    void convertsLikePhpIntCast() {
        assertThat(LegacyInput.intval("3abc")).isEqualTo(3);
        assertThat(LegacyInput.intval("+3")).isEqualTo(3);
        assertThat(LegacyInput.intval("3.9")).isEqualTo(3);
        assertThat(LegacyInput.intval(".5")).isZero();
        assertThat(LegacyInput.intval("0x1A")).isZero();
        assertThat(LegacyInput.intval("abc")).isZero();
        assertThat(LegacyInput.intval("1e2")).isEqualTo(100);
        assertThat(LegacyInput.intval("1e")).isEqualTo(1);
        assertThat(LegacyInput.intval("1e999")).isZero();
        assertThat(LegacyInput.intval("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("대소문자 변환은 ASCII 만 바꾼다")
    void changesAsciiCaseOnly() {
        assertThat(LegacyInput.lowerAscii("TitLE")).isEqualTo("title");
        assertThat(LegacyInput.upperAscii("m5-1")).isEqualTo("M5-1");
        assertThat(LegacyInput.lowerAscii("İ")).isEqualTo("İ");
    }
}
