package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    private static ItemSearchCondition condition(Map<String, String> values) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        values.forEach(params::add);
        return ItemSearchCondition.from(params);
    }

    @Test
    @DisplayName("search: 페이지 순서대로 행을 만들고 전체 건수를 담는다, 안내 문구는 없음")
    void searchKeepsPageOrder() {
        Item hard = ItemFixtures.item(4, unit, "난이도 4 문항", 4, ItemStatus.ACTIVE, ItemFixtures.tag(1, "계산"));
        Item easy = ItemFixtures.item(2, unit, "난이도 2 문항", 2, ItemStatus.ACTIVE);
        ItemSearchCondition condition = condition(Map.of());
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(hard, easy), condition.pageable(), 25));
        when(itemRepository.findWithDetailsByIdIn(List.of(4, 2))).thenReturn(List.of(easy, hard));

        ItemSearchResponse response = itemSearchService.search(condition);

        assertThat(response.items()).extracting(ItemSearchRow::id).containsExactly(4, 2);
        assertThat(response.items().get(0).unit()).isEqualTo("M5-1");
        assertThat(response.items().get(0).tags()).containsExactly("계산");
        assertThat(response.count()).isEqualTo(25);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 전체 건수 0 → 빈 행 + '검색 결과가 없습니다', 상세 조회는 하지 않는다")
    void searchReturnsMessageWhenNothingMatches() {
        ItemSearchCondition condition = condition(Map.of("unit", "Z9-99"));
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), condition.pageable(), 0));

        ItemSearchResponse response = itemSearchService.search(condition);

        assertThat(response.items()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemRepository, never()).findWithDetailsByIdIn(anyCollection());
    }

    @Test
    @DisplayName("search: 결과보다 뒤 페이지 → 행 없음 · 건수 유지 · 안내 문구 없음(BR-16)")
    void searchBeyondLastPageKeepsCount() {
        ItemSearchCondition condition = condition(Map.of("page", "2"));
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), condition.pageable(), 20));

        ItemSearchResponse response = itemSearchService.search(condition);

        assertThat(response.items()).isEmpty();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.message()).isNull();
    }
}
