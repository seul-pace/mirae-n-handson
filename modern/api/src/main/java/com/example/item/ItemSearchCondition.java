package com.example.item;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 조건. GET 파라미터를 레거시 {@code search.php} 와 같은 규칙(BR-02~BR-18)으로 정리한 값이다.
 * 잘못된 값도 요청을 거절하지 않고 레거시처럼 보정하거나 그대로 조회한다. 경고 문구는 응답에 넣지 않는다.
 *
 * @param keyword   제목 · 지문 부분 일치 키워드. 없으면 {@code null}
 * @param unitCode  단원 코드(정확히 일치). 없으면 {@code null}
 * @param level     난이도(정확히 일치). {@code null} 이면 {@code level < DEFAULT_LEVEL_LIMIT}
 * @param tag       태그 이름(정확히 일치). 없으면 {@code null}
 * @param sort      정렬 기준
 * @param direction 정렬 방향
 * @param page      페이지 번호 1~{@value #MAX_PAGE}
 * @param warnings  레거시가 화면에 띄우던 입력값 경고(DB 확인이 필요한 경고는 빠져 있다)
 */
public record ItemSearchCondition(
    String keyword,
    String unitCode,
    Long level,
    String tag,
    ItemSearchSort sort,
    Direction direction,
    int page,
    List<String> warnings) {

    /** 한 페이지 문항 수. */
    public static final int PAGE_SIZE = 20;
    /** 페이지 번호 상한. */
    public static final int MAX_PAGE = 999;
    /** 키워드 최대 글자 수. */
    public static final int MAX_KEYWORD_LENGTH = 100;
    /** 태그 이름 최대 글자 수. */
    public static final int MAX_TAG_LENGTH = 50;
    /** 난이도를 비웠을 때 적용하는 상한(미만). 레거시 주석은 "1~5 모두 포함"이지만 코드는 {@code level < 5} 다. */
    public static final int DEFAULT_LEVEL_LIMIT = 5;

    private static final Pattern UNIT_CODE_FORMAT = Pattern.compile("[A-Za-z][0-9]{1,2}-[0-9]{1,2}");
    private static final Pattern LEVEL_IN_RANGE = Pattern.compile("[1-5]");
    /** PHP {@code /^[0-9]+$/} — {@code $} 가 끝의 줄바꿈 하나를 허용한다. */
    private static final Pattern PAGE_DIGITS = Pattern.compile("[0-9]+\n?");

    public ItemSearchCondition {
        warnings = List.copyOf(warnings);
    }

    /** GET 파라미터 → 검색 조건. 배열 파라미터({@code q[]})는 첫 값을 쓴다(BR-18). */
    public static ItemSearchCondition from(MultiValueMap<String, String> params) {
        List<String> warnings = new ArrayList<>();
        String keyword = parseKeyword(orEmpty(LegacyInput.param(params, "q")), warnings);
        String unitCode = parseUnitCode(orEmpty(LegacyInput.param(params, "unit")), warnings);
        Long level = parseLevel(orEmpty(LegacyInput.param(params, "level")), warnings);
        String tag = parseTag(orEmpty(LegacyInput.param(params, "tag")), warnings);

        String dirParam = LegacyInput.lowerAscii(LegacyInput.trim(orEmpty(LegacyInput.param(params, "dir"))));
        if (!dirParam.equals("asc") && !dirParam.equals("desc")) {
            if (!dirParam.isEmpty()) {
                warnings.add("정렬 방향은 asc 또는 desc 만 가능합니다.");
            }
            dirParam = "";
        }
        String sortParam = LegacyInput.lowerAscii(LegacyInput.trim(orEmpty(LegacyInput.param(params, "sort"))));
        ItemSearchSort sort = ItemSearchSort.fromParam(sortParam);
        if (sort == null) {
            warnings.add("알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.");
            sort = ItemSearchSort.DEFAULT;
        }
        Direction direction = switch (dirParam) {
            case "asc" -> Direction.ASC;
            case "desc" -> Direction.DESC;
            default -> sort.defaultDirection();
        };

        String pageParam = LegacyInput.param(params, "page");
        int page = parsePage(pageParam == null ? "1" : pageParam, warnings);

        return new ItemSearchCondition(keyword, unitCode, level, tag, sort, direction, page, warnings);
    }

    /** 현재 페이지 · 정렬 → 저장소 페이지 요청({@code LIMIT 20 OFFSET (page-1)*20}). */
    public Pageable pageable() {
        return PageRequest.of(page - 1, PAGE_SIZE, toSort());
    }

    public Sort toSort() {
        return sort.toSort(direction);
    }

    /** BR-05 · BR-06: trim 뒤 100자로 자른다. 비면 조건 없음. */
    private static String parseKeyword(String raw, List<String> warnings) {
        String q = LegacyInput.trim(raw);
        if (LegacyInput.length(q) > MAX_KEYWORD_LENGTH) {
            q = LegacyInput.truncate(q, MAX_KEYWORD_LENGTH);
            warnings.add("키워드가 너무 길어 100자까지만 사용했습니다.");
        }
        if (q.isEmpty()) {
            return null;
        }
        if (LegacyInput.length(q) == 1) {
            warnings.add("키워드가 한 글자라 결과가 많을 수 있습니다.");
        }
        if (q.contains("%") || q.contains("_")) {
            warnings.add("키워드의 % 와 _ 는 와일드카드로 처리됩니다.");
        }
        return q;
    }

    /** BR-07 · BR-08: trim 만 하고 형식 · 대소문자가 틀려도 입력값 그대로 조회한다. */
    private static String parseUnitCode(String raw, List<String> warnings) {
        String unit = LegacyInput.trim(raw);
        if (unit.isEmpty()) {
            return null;
        }
        if (!UNIT_CODE_FORMAT.matcher(unit).matches()) {
            warnings.add("단원 코드 형식이 올바르지 않습니다. (예: M5-1)");
        }
        if (!unit.equals(LegacyInput.upperAscii(unit))) {
            warnings.add("단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)");
        }
        return unit;
    }

    /** BR-02 · BR-03 · BR-04: 비면 {@code null}(난이도 5 제외), 그 밖에는 PHP {@code (int)} 로 바꾼 값. */
    private static Long parseLevel(String raw, List<String> warnings) {
        String level = LegacyInput.trim(raw);
        if (level.isEmpty()) {
            return null;
        }
        if (!LEVEL_IN_RANGE.matcher(level).matches()) {
            warnings.add("난이도는 1~5 사이여야 합니다.");
        }
        return LegacyInput.intval(level);
    }

    /** BR-10: trim 뒤 50자로 자른다. {@code %} · {@code _} 검사는 자르기 전 값으로 한다. */
    private static String parseTag(String raw, List<String> warnings) {
        String tag = LegacyInput.trim(raw);
        if (tag.isEmpty()) {
            return null;
        }
        if (tag.contains("%") || tag.contains("_")) {
            warnings.add("태그는 부분 일치를 지원하지 않습니다.");
        }
        if (LegacyInput.length(tag) > MAX_TAG_LENGTH) {
            tag = LegacyInput.truncate(tag, MAX_TAG_LENGTH);
            warnings.add("태그 이름이 너무 길어 50자까지만 사용했습니다.");
        }
        return tag;
    }

    /** BR-15: 숫자만 받는다(trim 하지 않음). 0 이하는 경고 없이 1, 999 초과는 999. */
    private static int parsePage(String raw, List<String> warnings) {
        long page = 1;
        if (PAGE_DIGITS.matcher(raw).matches()) {
            page = LegacyInput.intval(raw);
        } else if (!raw.isEmpty() && !raw.equals("1")) {
            warnings.add("페이지 번호가 올바르지 않아 1페이지를 표시합니다.");
        }
        if (page < 1) {
            page = 1;
        }
        if (page > MAX_PAGE) {
            page = MAX_PAGE;
            warnings.add("페이지 번호는 999 를 넘을 수 없습니다.");
        }
        return (int) page;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
