package com.example.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 서비스 — 레거시 {@code search.php} 이관. 잘못된 조건도 거절하지 않고 0건으로 응답한다.
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    /** 전체 건수가 0일 때의 안내 문구(레거시 {@code <p id="message">}). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemRepository itemRepository;
    private final UnitRepository unitRepository;
    private final TagRepository tagRepository;

    public ItemSearchService(ItemRepository itemRepository, UnitRepository unitRepository,
                             TagRepository tagRepository) {
        this.itemRepository = itemRepository;
        this.unitRepository = unitRepository;
        this.tagRepository = tagRepository;
    }

    /** 조건에 맞는 공개 문항의 현재 페이지와 전체 건수. */
    public ItemSearchResponse search(ItemSearchCondition condition) {
        if (log.isDebugEnabled()) {
            List<String> warnings = collectWarnings(condition);
            if (!warnings.isEmpty()) {
                log.debug("item search warnings {}", warnings);
            }
        }
        Page<Item> page = itemRepository.findAll(ItemSpecifications.matching(condition), condition.pageable());
        List<ItemSearchRow> rows = loadRows(page.getContent());
        long count = page.getTotalElements();
        log.debug("item search matched {} items, page {} has {} rows", count, condition.page(), rows.size());
        // BR-16: 안내 문구는 현재 페이지 행 수가 아니라 전체 건수로 판단한다
        return new ItemSearchResponse(rows, count, count == 0 ? NO_RESULT_MESSAGE : null);
    }

    /**
     * 레거시가 화면에 띄우던 경고 전체. 입력값 경고에 DB 확인이 필요한 경고(BR-09 미등록 단원, BR-11 미등록 태그)를 더한다.
     * 응답에는 넣지 않는다.
     */
    List<String> collectWarnings(ItemSearchCondition condition) {
        List<String> warnings = new ArrayList<>(condition.warnings());
        String unitCode = condition.unitCode();
        if (unitCode != null && unitRepository.findByCode(unitCode).isEmpty()) {
            warnings.add("등록되지 않은 단원 코드입니다: " + unitCode);
        }
        String tag = condition.tag();
        // BR-11: 레거시는 태그 목록과 PHP === (대소문자 구분)로 비교한다
        if (tag != null && tagRepository.findByName(tag).map(Tag::getName).filter(tag::equals).isEmpty()) {
            warnings.add("등록되지 않은 태그입니다: " + tag);
        }
        return warnings;
    }

    /** 현재 페이지 문항(최대 20건)의 단원 · 태그를 한 번에 읽어 페이지 순서대로 행을 만든다. */
    private List<ItemSearchRow> loadRows(List<Item> pageItems) {
        if (pageItems.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = pageItems.stream().map(Item::getId).toList();
        Map<Integer, Item> loaded = itemRepository.findWithDetailsByIdIn(ids).stream()
            .collect(Collectors.toMap(Item::getId, Function.identity()));
        return ids.stream().map(loaded::get).map(ItemSearchRow::from).toList();
    }
}
