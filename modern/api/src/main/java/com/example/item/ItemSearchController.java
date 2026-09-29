package com.example.item;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API — 레거시 {@code search.php} 이관. 파라미터 이름은 레거시와 같다(q, unit, level, tag, sort, dir, page).
 * PHP 배열 파라미터({@code q[]})까지 받기 위해 파라미터 전체를 받아 {@link ItemSearchCondition} 으로 넘긴다.
 */
@RestController
@RequestMapping("/api/items")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /** {@code GET /api/items/search} — 공개 문항 검색. 조건이 이상해도 200 과 0건으로 응답한다. */
    @GetMapping("/search")
    public ItemSearchResponse search(@RequestParam MultiValueMap<String, String> params) {
        return itemSearchService.search(ItemSearchCondition.from(params));
    }
}
