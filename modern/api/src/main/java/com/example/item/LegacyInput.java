package com.example.item;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;

/**
 * 레거시 문항 검색(PHP 7.4, {@code legacy/item-bank-php/search.php})의 입력 처리 함수를 같은 결과가 나오게 옮긴 것.
 * Java 기본 함수와 결과가 다른 곳(trim 대상 문자, 글자 수 기준, 문자열 → 정수 변환)만 따로 둔다.
 */
final class LegacyInput {

    /** PHP {@code trim()} 이 지우는 문자: 공백, \t, \n, \r, \0, \x0B. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";

    /** PHP 숫자 문자열 앞부분 — 부호, 정수부 · 소수부, 지수. */
    private static final Pattern NUMERIC_PREFIX =
        Pattern.compile("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    private LegacyInput() {
    }

    /**
     * GET 파라미터 한 개. PHP 처럼 {@code name[]} 배열이면 첫 값, 같은 이름이 반복되면 마지막 값을 쓴다.
     * 없으면 {@code null}.
     */
    static String param(MultiValueMap<String, String> params, String name) {
        List<String> array = params.get(name + "[]");
        if (array != null && !array.isEmpty()) {
            return nullToEmpty(array.get(0));
        }
        List<String> values = params.get(name);
        if (values != null && !values.isEmpty()) {
            return nullToEmpty(values.get(values.size() - 1));
        }
        return null;
    }

    /** PHP {@code trim()} — {@link String#trim()} 과 달리 다른 제어 문자는 남긴다. */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** PHP {@code mb_strlen($s, 'UTF-8')} — 코드포인트 수. */
    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /** PHP {@code mb_substr($s, 0, $max, 'UTF-8')} — 코드포인트 기준으로 앞에서 자른다. */
    static String truncate(String value, int max) {
        if (length(value) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** PHP {@code strtolower()}(C 로캘) — ASCII 대문자만 바꾼다. */
    static String lowerAscii(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /** PHP {@code strtoupper()}(C 로캘) — ASCII 소문자만 바꾼다. */
    static String upperAscii(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /**
     * PHP 7.4 {@code (int)$string}. 앞의 공백을 건너뛰고 숫자로 읽을 수 있는 앞부분만 쓴다.
     * 숫자로 시작하지 않으면 0, 소수 · 지수는 소수점 아래를 버리고, 범위를 넘으면 long 의 최댓값 · 최솟값으로 고정한다.
     * 예: {@code "3abc"} → 3, {@code "abc"} → 0, {@code "1e2"} → 100, {@code "3.9"} → 3.
     */
    static long intval(String value) {
        int start = 0;
        while (start < value.length() && " \t\n\r\u000B\f".indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        Matcher m = NUMERIC_PREFIX.matcher(value.substring(start));
        if (!m.find()) {
            return 0L;
        }
        String number = m.group();
        if (number.indexOf('.') < 0 && m.group(1) == null) {
            return clamp(new BigInteger(number.startsWith("+") ? number.substring(1) : number));
        }
        double d = Double.parseDouble(number);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return 0L;
        }
        return clamp(new BigDecimal(d).toBigInteger());
    }

    private static long clamp(BigInteger value) {
        if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
