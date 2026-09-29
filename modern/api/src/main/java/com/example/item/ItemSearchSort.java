package com.example.item;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

/**
 * 문항 검색 정렬 기준(레거시 {@code search.php} 의 {@code sort} 허용 목록).
 * {@code id} 외에는 {@code id ASC} 를 2차 키로 두고, {@code unit} 은 단원 안에서 {@code level DESC} 를 먼저 적용한다.
 */
public enum ItemSearchSort {

    /** 기본 정렬 — 난이도 내림차순, 같은 난이도면 id 오름차순. 방향 파라미터는 무시한다. */
    DEFAULT("", Direction.DESC),
    ID("id", Direction.ASC),
    TITLE("title", Direction.ASC),
    UNIT("unit", Direction.ASC),
    LEVEL("level", Direction.DESC),
    CREATED("created", Direction.DESC);

    private final String param;
    private final Direction defaultDirection;

    ItemSearchSort(String param, Direction defaultDirection) {
        this.param = param;
        this.defaultDirection = defaultDirection;
    }

    /** 소문자로 바꾼 {@code sort} 값 → 정렬 기준. 목록에 없으면 {@code null}. */
    static ItemSearchSort fromParam(String sort) {
        for (ItemSearchSort value : values()) {
            if (value.param.equals(sort)) {
                return value;
            }
        }
        return null;
    }

    /** {@code dir} 이 비어 있을 때의 방향 — 난이도 · 등록일은 내림차순, 나머지는 오름차순. */
    Direction defaultDirection() {
        return defaultDirection;
    }

    Sort toSort(Direction direction) {
        Sort byId = Sort.by(Direction.ASC, "id");
        return switch (this) {
            case DEFAULT -> Sort.by(Direction.DESC, "level").and(byId);
            case ID -> Sort.by(direction, "id");
            case TITLE -> Sort.by(direction, "title").and(byId);
            case UNIT -> Sort.by(direction, "unit.code").and(Sort.by(Direction.DESC, "level")).and(byId);
            case LEVEL -> Sort.by(direction, "level").and(byId);
            case CREATED -> Sort.by(direction, "createdAt").and(byId);
        };
    }
}
