package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 검색 파라미터 정리 규칙 — 레거시 search.php 와 같은 보정 · 경고. */
class ItemSearchConditionTest {

    private static MultiValueMap<String, String> params(Map<String, String> values) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        values.forEach(map::add);
        return map;
    }

    private static ItemSearchCondition parse(Map<String, String> values) {
        return ItemSearchCondition.from(params(values));
    }

    @Test
    @DisplayName("파라미터가 없으면 조건 없음 · 난이도 5 제외 · 기본 정렬 · 1페이지")
    void usesDefaultsWithoutParams() {
        ItemSearchCondition condition = parse(Map.of());

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isNull();
        assertThat(condition.level()).isNull();
        assertThat(condition.tag()).isNull();
        assertThat(condition.sort()).isEqualTo(ItemSearchSort.DEFAULT);
        assertThat(condition.page()).isEqualTo(1);
        assertThat(condition.warnings()).isEmpty();
        assertThat(condition.toSort()).isEqualTo(Sort.by(Direction.DESC, "level").and(Sort.by("id")));
    }

    @Test
    @DisplayName("빈 문자열 · 공백만 있는 값은 조건 없음으로 본다")
    void treatsBlankValuesAsNoCondition() {
        ItemSearchCondition condition = parse(Map.of("q", "   ", "unit", " ", "level", " ", "tag", "", "page", ""));

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isNull();
        assertThat(condition.level()).isNull();
        assertThat(condition.tag()).isNull();
        assertThat(condition.page()).isEqualTo(1);
        assertThat(condition.warnings()).isEmpty();
    }

    @Test
    @DisplayName("배열 파라미터 q[] 는 첫 값, 같은 이름이 반복되면 마지막 값을 쓴다")
    void picksFirstArrayValueAndLastRepeatedValue() {
        MultiValueMap<String, String> array = new LinkedMultiValueMap<>();
        array.add("q[]", "분수");
        array.add("q[]", "소수");
        MultiValueMap<String, String> repeated = new LinkedMultiValueMap<>();
        repeated.add("q", "분수");
        repeated.add("q", "소수");

        assertThat(ItemSearchCondition.from(array).keyword()).isEqualTo("분수");
        assertThat(ItemSearchCondition.from(repeated).keyword()).isEqualTo("소수");
    }

    @Test
    @DisplayName("키워드는 trim 뒤 100자(코드포인트)로 자르고 경고한다")
    void truncatesKeywordToHundredCodePoints() {
        String keyword101 = "분수" + "가".repeat(99);
        String emoji100 = "😀".repeat(100);

        ItemSearchCondition truncated = parse(Map.of("q", "  " + keyword101 + "  "));
        ItemSearchCondition exact = parse(Map.of("q", emoji100));

        assertThat(truncated.keyword()).isEqualTo(keyword101.substring(0, 100));
        assertThat(truncated.warnings()).contains("키워드가 너무 길어 100자까지만 사용했습니다.");
        assertThat(exact.keyword()).isEqualTo(emoji100);
        assertThat(exact.warnings()).isEmpty();
    }

    @Test
    @DisplayName("키워드의 % · _ 는 그대로 두고 경고만 한다")
    void keepsWildcardsInKeyword() {
        ItemSearchCondition condition = parse(Map.of("q", "%"));

        assertThat(condition.keyword()).isEqualTo("%");
        assertThat(condition.warnings()).containsExactly(
            "키워드가 한 글자라 결과가 많을 수 있습니다.", "키워드의 % 와 _ 는 와일드카드로 처리됩니다.");
    }

    @Test
    @DisplayName("난이도는 PHP (int) 변환 규칙을 따르고 1~5 한 글자가 아니면 경고한다")
    void convertsLevelLikePhpIntCast() {
        assertThat(parse(Map.of("level", "3")).level()).isEqualTo(3L);
        assertThat(parse(Map.of("level", "3")).warnings()).isEmpty();
        assertThat(parse(Map.of("level", "3abc")).level()).isEqualTo(3L);
        assertThat(parse(Map.of("level", "3abc")).warnings()).containsExactly("난이도는 1~5 사이여야 합니다.");
        assertThat(parse(Map.of("level", "6")).level()).isEqualTo(6L);
        assertThat(parse(Map.of("level", "-1")).level()).isEqualTo(-1L);
        assertThat(parse(Map.of("level", "abc")).level()).isEqualTo(0L);
        assertThat(parse(Map.of("level", "1e2")).level()).isEqualTo(100L);
        assertThat(parse(Map.of("level", "99999999999999999999")).level()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("단원 코드는 형식 · 대소문자가 틀려도 입력값 그대로 쓰고 경고만 한다")
    void keepsUnitCodeAsIs() {
        ItemSearchCondition lower = parse(Map.of("unit", " m5-1 "));
        ItemSearchCondition malformed = parse(Map.of("unit", "ABC"));

        assertThat(lower.unitCode()).isEqualTo("m5-1");
        assertThat(lower.warnings()).containsExactly("단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)");
        assertThat(malformed.unitCode()).isEqualTo("ABC");
        assertThat(malformed.warnings()).containsExactly("단원 코드 형식이 올바르지 않습니다. (예: M5-1)");
    }

    @Test
    @DisplayName("태그는 trim 뒤 50자로 자른다")
    void truncatesTagToFiftyCharacters() {
        String tag200 = "계산" + "가".repeat(198);

        ItemSearchCondition condition = parse(Map.of("tag", tag200));

        assertThat(condition.tag()).isEqualTo(tag200.substring(0, 50));
        assertThat(condition.warnings()).containsExactly("태그 이름이 너무 길어 50자까지만 사용했습니다.");
    }

    @Test
    @DisplayName("페이지: 0 은 경고 없이 1, 음수 · 앞 공백은 경고 후 1, 999 초과는 999, 끝의 줄바꿈 하나는 허용")
    void clampsPageLikeLegacy() {
        assertThat(parse(Map.of("page", "0")).page()).isEqualTo(1);
        assertThat(parse(Map.of("page", "0")).warnings()).isEmpty();
        assertThat(parse(Map.of("page", "-1")).page()).isEqualTo(1);
        assertThat(parse(Map.of("page", "-1")).warnings()).containsExactly("페이지 번호가 올바르지 않아 1페이지를 표시합니다.");
        assertThat(parse(Map.of("page", " 2")).page()).isEqualTo(1);
        assertThat(parse(Map.of("page", "2\n")).page()).isEqualTo(2);
        assertThat(parse(Map.of("page", "999")).page()).isEqualTo(999);
        assertThat(parse(Map.of("page", "1000")).page()).isEqualTo(999);
        assertThat(parse(Map.of("page", "1000")).warnings()).containsExactly("페이지 번호는 999 를 넘을 수 없습니다.");
        assertThat(parse(Map.of("page", "99999999999999999999")).page()).isEqualTo(999);
        assertThat(parse(Map.of("page", "3")).pageable().getOffset()).isEqualTo(40);
    }

    @Test
    @DisplayName("정렬: 목록에 없는 값은 기본 정렬, 방향이 없으면 난이도 · 등록일만 내림차순")
    void resolvesSortAndDirection() {
        ItemSearchCondition unknown = parse(Map.of("sort", "price", "dir", "up"));
        ItemSearchCondition title = parse(Map.of("sort", " TITLE "));
        ItemSearchCondition level = parse(Map.of("sort", "level"));
        ItemSearchCondition unitDesc = parse(Map.of("sort", "unit", "dir", "DESC"));

        assertThat(unknown.sort()).isEqualTo(ItemSearchSort.DEFAULT);
        assertThat(unknown.warnings()).containsExactly(
            "정렬 방향은 asc 또는 desc 만 가능합니다.", "알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.");
        assertThat(title.toSort()).isEqualTo(Sort.by(Direction.ASC, "title").and(Sort.by("id")));
        assertThat(level.toSort()).isEqualTo(Sort.by(Direction.DESC, "level").and(Sort.by("id")));
        assertThat(unitDesc.toSort()).isEqualTo(
            Sort.by(Direction.DESC, "unit.code").and(Sort.by(Direction.DESC, "level")).and(Sort.by("id")));
    }
}
