# 이관 검증 3차 — item 검색 엔드포인트

- 검증 대상: `git diff upstream/main...HEAD -- modern characterization` (16개 파일, +3373 / -1)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 수행: reviewer Sub-agent(체크리스트 대조) + tester Sub-agent(테스트 실행) 보고를 받아 메인 세션이 최종 판정.

판정: 승인 (재검증 반영. 최초 판정은 반려였고 아래 "재검증" 참고)

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `./gradlew cleanTest test` 52 통과 · 0 실패 · 0 오류 · 0 건너뜀(두 번 실행, 모두 성공). characterization 레거시 대상 39 통과 · 0 실패, 새 API 대상 38 통과 · 1 건너뜀 · 0 실패. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. reviewer 가 비밀값 · 로그 · 쿼리 · 인증 · DB 변경 등 체크리스트 2-1 ~ 2-10 을 모두 해당 없음으로 확인. |
| 요청 범위 이탈 없음 | 충족 | 지정 범위 16개 파일은 모두 item 검색 이관과 그 테스트다. `db/` · `legacy/` · `build.gradle` · `application.yml` 변경 없음. 단 이슈 #1(경고)은 "동작 보존" 관점의 차이이므로 사람의 판단이 필요하다. |
| 컨벤션 준수 | 충족 | reviewer 가 4-2 ~ 4-10 위반 없음으로 확인(setter 0, 빈 catch 없음, record · `.toList()`, 로그 `{}` · DEBUG, `@Test` 25개 모두 `@DisplayName`). |

## 재검증 (반려 사유: 새 API 대상 결과의 HEAD 기준 불확실)

- 최초 판정은 8080 서버 시작(13:06)이 `ItemSearchService.class` 컴파일(16:49)보다 앞서 있어 "테스트 통과"를 미충족으로 뒀다.
- 이후 서버가 17:18:06 에 재기동됐다. 그 뒤로 바뀐 `src/main` 소스는 없다(메인 세션이 `find -newer` 로 확인). 따라서 서버는 현재 HEAD 소스를 반영한다.
- tester 가 같은 서버에서 아래 세 명령을 다시 실행했다. Gradle 은 `cleanTest` 로 캐시를 배제했고, 수치는 이번 실행 출력의 집계다.

| 명령 | 결과 |
|---|---|
| `cd modern/api && ./gradlew cleanTest test` | 통과 52 · 실패 0 · 오류 0 · 건너뜀 0 |
| `cd characterization && npm test` (레거시 대상) | 39 통과 · 0 실패 · 0 건너뜀 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API 대상) | 38 통과 · 1 건너뜀 · 0 실패 |

- 건너뜀 1건은 `tests/example-units.test.js` 의 `단원 목록 전체 — 표의 행 · 건수 · 안내 문구` 이며 `it.skipIf(legacyOnly)` 로 새 API 대상에서 건너뛰는 레거시 전용 케이스다. 이번 이관의 `item-bank.test.js` 에는 skipIf 가 없다.
- `characterization/` 의 테스트 코드와 스냅샷은 수정하지 않았다. 두 파일은 `cb0fab9` 한 커밋에서 생성됐고 `git diff cb0fab9 HEAD -- characterization` 이 비어 있어, 스냅샷을 고쳐 통과시킨 흔적은 없다.
- 사용자가 직접 돌렸다는 결과의 수치는 전달받지 못해 근거로 쓰지 않았다. 위 수치는 tester 의 재실행 결과다.
- 승인과 별개로 아래 이슈 #1(경고)의 수용 여부는 사람이 정해야 한다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:33-35` (`ItemSearchResponse.java:12`, `ItemSearchCondition.java:14`) | 레거시가 화면에 띄우던 입력 경고를 `warnings` 로 만들지만 응답에 싣지 않고 DEBUG 로그로만 남긴다. "동작을 바꾸지 않고 이관"과 차이가 난다. characterization 정규화 결과에 경고가 없어 테스트는 통과할 수 있다. | 의도된 생략이면 의심 동작 · 차이 목록에 올려 사람이 수용 여부를 정한다. 보존이 필요하면 `warnings` 필드 추가와 정규화 · 스냅샷 범위를 먼저 협의한다. 스냅샷을 임의로 고치지 않는다. |
| 2 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:51-52` | 페이지 조회와 상세 조회가 별도 쿼리라, 그 사이 행이 사라지면 `loaded::get` 이 `null` 이 되어 `ItemSearchRow.from` 에서 NPE 가 난다. 구체적 재현 입력은 없다. | `null` 이면 건너뛰거나 도메인 예외로 바꾼다. |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:47` | 정규식 `[1-5]` 가 난이도 상한을 리터럴로 품고 있고, 이름 붙은 상수(`DEFAULT_LEVEL_LIMIT = 5`, 44행)와 따로 논다. | 레거시 허용 범위를 상수로 이름 붙이는 방안을 검토한다. |

## 의심 동작 (레거시 버그로 보이나 고치지 않고 보존한 것)

1. 난이도를 비우면 난이도 5 문항이 제외된다. 레거시 주석은 "1~5 모두 포함"인데 코드는 `level < 5` 다(`ItemSearchCondition.java:43`, `ItemSpecifications.java:51`, BR-02). 5 는 `level=5` 를 명시해야 나온다.
2. 페이지 번호 검사는 PHP `$` 처럼 끝의 줄바꿈 하나를 허용한다(`ItemSearchCondition.java:48-49`).
3. 0 이하 페이지는 경고 없이 1로 보정하고 999 초과만 경고한다(`ItemSearchCondition.java:159`, `:167`).
4. 난이도는 `(int)` 변환이라 `3abc` 가 3, `abc` 가 0, `1e2` 가 100 이 된다. 경고는 뜨지만 그대로 조회한다(`ItemSearchCondition.java:131-141`, `LegacyInput.java:91`).
5. 키워드의 `%` · `_` 를 이스케이프하지 않는다(`ItemSpecifications.java:31-39`, BR-06).
6. 소문자 단원 코드 `m5-1` 이 DB 콜레이션(`utf8mb4_unicode_ci`)의 대소문자 무시 비교로 대문자와 같은 결과가 된다. 경고는 뜬다(`ItemSearchCondition.java:116-129`, BR-07 · 08).
7. 결과보다 뒤 페이지를 요청하면 행은 없고 건수는 유지되며 안내 문구도 없다(`ItemSearchService.java:40-41`, BR-16).
8. 안내 문구는 전체 건수 0일 때만 나온다. 같은 파라미터가 반복되면 마지막 값, `q[]` 배열이면 첫 값을 쓴다(`LegacyInput.java:26-40`, BR-18).

## 확인 필요

- characterization 스냅샷이 실제 레거시 응답에서 찍힌 것인지. 이 브랜치가 만든 베이스라인이라 diff 만으로는 알 수 없다. 사람이 베이스라인 시점의 레거시 대상을 확인하면 좋다.
- 비교 범위 밖(`docs/`, `CLAUDE.md`, `.claude/skills/`, `templates/` 등 23개 파일, 이관 커밋의 `docs/item-bank/BUSINESS-RULES.md` 포함)은 이번 판정에서 제외했다. migration-review-2 의 범위 이탈 지적은 전체 범위에서는 그대로 유효하다.
- tester 는 새 테스트 파일을 추가하지 않았다(기존 테스트가 새 public 메서드를 모두 호출한다고 판단). `ItemSpecifications` · `ItemSearchSort` 의 분기는 전용 테스트 없이 `ItemSearchRepositoryTest` · `ItemSearchConditionTest` 로 간접 검증되며, tester 는 그 분기를 직접 확인하지 않았다.
