package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 문항 검색 컨트롤러 슬라이스 — 응답 모양은 동작 보존 테스트의 정규화 결과({items, count, message})와 같다. */
@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("GET /api/items/search → 200, 행에는 id · title · unit · level · tags 만")
    void searchReturnsRowsWithNormalizedFieldsOnly() throws Exception {
        when(itemSearchService.search(any(ItemSearchCondition.class))).thenReturn(new ItemSearchResponse(
            List.of(new ItemSearchRow(24, "분수 덧셈과 뺄셈 혼합 계산", "M5-1", 5, List.of("계산", "오답률높음"))), 6, null));

        mockMvc.perform(get("/api/items/search").param("level", "5"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.count").value(6))
            .andExpect(jsonPath("$.message").isEmpty())
            .andExpect(jsonPath("$.items[0].id").value(24))
            .andExpect(jsonPath("$.items[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.items[0].level").value(5))
            .andExpect(jsonPath("$.items[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.items[0].length()").value(5))
            .andExpect(jsonPath("$.items[0].stem").doesNotExist())
            .andExpect(jsonPath("$.items[0].status").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/items/search 0건 → 404 가 아니라 200 과 안내 문구")
    void searchWithNoResultReturns200() throws Exception {
        when(itemSearchService.search(any(ItemSearchCondition.class)))
            .thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("unit", "Z9-99").param("level", "abc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("GET /api/items/search 파라미터를 레거시 이름 그대로 받고 q[] 는 첫 값을 쓴다")
    void searchPassesLegacyParams() throws Exception {
        when(itemSearchService.search(any(ItemSearchCondition.class)))
            .thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search")
                .param("q[]", "분수", "소수")
                .param("unit", "M5-1")
                .param("level", "3")
                .param("tag", "계산")
                .param("sort", "id")
                .param("dir", "desc")
                .param("page", "2"))
            .andExpect(status().isOk());

        ArgumentCaptor<ItemSearchCondition> captor = ArgumentCaptor.forClass(ItemSearchCondition.class);
        verify(itemSearchService).search(captor.capture());
        ItemSearchCondition condition = captor.getValue();
        assertThat(condition.keyword()).isEqualTo("분수");
        assertThat(condition.unitCode()).isEqualTo("M5-1");
        assertThat(condition.level()).isEqualTo(3L);
        assertThat(condition.tag()).isEqualTo("계산");
        assertThat(condition.sort()).isEqualTo(ItemSearchSort.ID);
        assertThat(condition.page()).isEqualTo(2);
    }
}
