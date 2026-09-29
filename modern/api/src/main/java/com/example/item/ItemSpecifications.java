package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * 문항 검색 조건 → JPA {@link Specification}. 레거시 뷰 {@code v_item_public} 과 같은 대상(공개 문항)을 읽는다.
 * 문자열 비교(단원 코드 · 태그 · 키워드 LIKE)는 DB 에 맡긴다 — MariaDB 콜레이션({@code utf8mb4_unicode_ci})에 따른
 * 대소문자 무시 등이 레거시와 같게 나오도록 Java 에서 비교하지 않는다.
 */
final class ItemSpecifications {

    private ItemSpecifications() {
    }

    static Specification<Item> matching(ItemSearchCondition condition) {
        return Specification.where(isPublic())
            .and(keyword(condition.keyword()))
            .and(unitCode(condition.unitCode()))
            .and(level(condition.level()))
            .and(tag(condition.tag()));
    }

    /** BR-01: 공개({@code status='A'}) 문항만. */
    static Specification<Item> isPublic() {
        return (root, query, cb) -> cb.equal(root.get("status"), ItemStatus.ACTIVE);
    }

    /** BR-06: 제목 또는 지문 부분 일치. {@code %} · {@code _} 를 이스케이프하지 않는다(ESCAPE 절도 없음). */
    static Specification<Item> keyword(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null) {
                return null;
            }
            String pattern = "%" + keyword + "%";
            return cb.or(cb.like(root.get("title"), pattern), cb.like(root.get("stem"), pattern));
        };
    }

    /** BR-07: 단원 코드 정확히 일치. */
    static Specification<Item> unitCode(String unitCode) {
        return (root, query, cb) -> unitCode == null ? null : cb.equal(root.get("unit").get("code"), unitCode);
    }

    /** BR-02 · BR-03 · BR-04: 비면 난이도 5 미만, 아니면 같은 난이도. int 범위 밖 값은 어떤 문항과도 같지 않다. */
    static Specification<Item> level(Long level) {
        return (root, query, cb) -> {
            if (level == null) {
                return cb.lessThan(root.get("level"), ItemSearchCondition.DEFAULT_LEVEL_LIMIT);
            }
            if (level < Integer.MIN_VALUE || level > Integer.MAX_VALUE) {
                return cb.disjunction();
            }
            return cb.equal(root.get("level"), level.intValue());
        };
    }

    /** BR-10: 이름이 정확히 같은 태그가 붙은 문항만({@code EXISTS}). */
    static Specification<Item> tag(String tag) {
        return (root, query, cb) -> {
            if (tag == null) {
                return null;
            }
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Item> tagged = sub.correlate(root);
            Join<Item, Tag> tags = tagged.join("tags");
            sub.select(tags.get("id")).where(cb.equal(tags.get("name"), tag));
            return cb.exists(sub);
        };
    }
}
