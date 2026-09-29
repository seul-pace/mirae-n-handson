package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답.
 *
 * @param items   현재 페이지 행(최대 20건)
 * @param count   조건에 맞는 전체 건수(페이지와 무관)
 * @param message 전체 건수가 0이면 "검색 결과가 없습니다", 아니면 {@code null}
 */
public record ItemSearchResponse(List<ItemSearchRow> items, long count, String message) {
}
