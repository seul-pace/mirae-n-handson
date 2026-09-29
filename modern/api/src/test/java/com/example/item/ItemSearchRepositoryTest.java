package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 검색 Specification 을 H2(MariaDB 모드)에 실제로 날린다.
 * 대소문자 무시 비교처럼 MariaDB 콜레이션에 기대는 동작은 H2 에서 재현되지 않으므로 동작 보존 테스트로 확인한다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, calc, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 분수", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수", 4, ItemStatus.DELETED, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.persist(ItemFixtures.item(null, decimal, "100% 활용", 4, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private Page<Item> search(Map<String, String> values) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        values.forEach(params::add);
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        return itemRepository.findAll(ItemSpecifications.matching(condition), condition.pageable());
    }

    private static List<String> titles(Page<Item> page) {
        return page.getContent().stream().map(Item::getTitle).toList();
    }

    @Test
    @DisplayName("기본 조건: 공개 문항만 · 난이도 5 제외 · level DESC, id ASC")
    void defaultSearchExcludesLevelFiveAndHiddenItems() {
        Page<Item> page = search(Map.of());

        assertThat(titles(page)).containsExactly("분수 문장제", "100% 활용", "분모가 같은 분수의 덧셈", "소수 곱셈");
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("난이도 5 를 명시해야 난이도 5 문항이 나온다")
    void explicitLevelFiveReturnsLevelFiveItems() {
        assertThat(titles(search(Map.of("level", "5")))).containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("범위 밖 · int 범위를 넘는 난이도는 오류 없이 0건")
    void outOfRangeLevelMatchesNothing() {
        assertThat(search(Map.of("level", "6")).getTotalElements()).isZero();
        assertThat(search(Map.of("level", "99999999999")).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("키워드는 제목 또는 지문 부분 일치, % 는 와일드카드")
    void keywordMatchesTitleOrStemWithWildcards() {
        assertThat(titles(search(Map.of("q", "분수", "sort", "id"))))
            .containsExactly("분모가 같은 분수의 덧셈", "분수 문장제");
        assertThat(search(Map.of("q", "문제 본문")).getTotalElements()).isEqualTo(4);
        assertThat(search(Map.of("q", "%")).getTotalElements()).isEqualTo(4);
        assertThat(search(Map.of("q", "분_")).getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("단원 코드 정확히 일치, 없는 단원은 0건")
    void unitCodeMatchesExactly() {
        assertThat(titles(search(Map.of("unit", "M5-3")))).containsExactly("100% 활용", "소수 곱셈");
        assertThat(search(Map.of("unit", "Z9-99")).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("태그 이름 정확히 일치(EXISTS)")
    void tagMatchesExactName() {
        assertThat(titles(search(Map.of("tag", "계산", "sort", "id", "dir", "desc"))))
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(search(Map.of("tag", "계")).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("단원 정렬은 단원 안에서 난이도 내림차순")
    void unitSortOrdersByLevelWithinUnit() {
        assertThat(titles(search(Map.of("sort", "unit", "dir", "desc"))))
            .containsExactly("100% 활용", "소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("마지막 페이지 뒤: 행은 없고 전체 건수는 유지")
    void pageBeyondLastKeepsTotal() {
        Page<Item> page = search(Map.of("page", "2"));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("findWithDetailsByIdIn: 단원 · 태그를 함께 로드하고 태그는 id 순")
    void findWithDetailsByIdInLoadsTags() {
        List<Integer> ids = search(Map.of("q", "문장제")).getContent().stream().map(Item::getId).toList();
        entityManager.clear();

        List<Item> items = itemRepository.findWithDetailsByIdIn(ids);

        assertThat(items).hasSize(1);
        assertThat(Hibernate.isInitialized(items.get(0).getTags())).isTrue();
        assertThat(items.get(0).getUnit().getCode()).isEqualTo("M5-1");
        assertThat(items.get(0).getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }
}
