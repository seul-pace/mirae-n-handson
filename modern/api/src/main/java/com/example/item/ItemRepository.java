package com.example.item;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Integer>, JpaSpecificationExecutor<Item> {

    /** 단건 조회 — 단원 · 태그를 함께 가져온다(OSIV 꺼져 있음). */
    @EntityGraph(attributePaths = {"unit", "tags"})
    Optional<Item> findWithDetailsById(Integer id);

    /** 여러 건 조회 — 단원 · 태그를 함께 가져온다. 순서는 보장하지 않는다(검색 결과 페이지의 행 채우기용). */
    @EntityGraph(attributePaths = {"unit", "tags"})
    List<Item> findWithDetailsByIdIn(Collection<Integer> ids);

    /** 단원 코드 + 상태로 조회. 정렬은 난이도 내림차순, 같은 난이도면 id 오름차순. */
    @EntityGraph(attributePaths = {"unit", "tags"})
    @Query("""
        select i from Item i
        where i.unit.code = :unitCode and i.status = :status
        order by i.level desc, i.id asc
        """)
    List<Item> findByUnitCodeAndStatus(@Param("unitCode") String unitCode, @Param("status") String status);

    long countByUnitIdAndStatus(Integer unitId, String status);
}
