# 머지 전 검증 2회차 — `upstream/main...HEAD`

요청 범위: 레거시 엔드포인트 1개(`search.php`)를 동작을 바꾸지 않고 modern 으로 이관.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew cleanTest test` 52건 통과 · 실패 0 · 오류 0 · 건너뜀 0. `cd characterization && npm test`(레거시 8081) 39건 통과 · 실패 0. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) 38건 통과 · 실패 0 · 1건 건너뜀(`example-units.test.js` 의 `units.php` 전용 케이스, `it.skipIf(legacyOnly)` 로 의도된 것). `item-bank.test.js` 는 건너뛰기 없음. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 · SQL 문자열 결합(Specification 사용, 값은 바인딩) · 삭제 코드 없음. |
| 요청 범위 이탈 없음 | 미충족 | #1. 요청은 엔드포인트 1개 이관인데 범위(`upstream/main...HEAD`)에 이관과 무관한 파일 13개가 섞여 있다. |
| 컨벤션 준수 | 충족 | 위반 없음. 점검 항목: setter · `System.out` · `printStackTrace` · 와일드카드 import · `Collectors.toList` · 빈 catch · `@Disabled` 0건, 120자 초과 줄 0건, 테스트 클래스 5개 모두 `@DisplayName` 사용, 컨트롤러는 서비스만 주입, DTO `from` 팩토리 사용, 로그 `{}` 자리표시자 · 영어 소문자 시작. |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `docs/assignment/ARCHITECTURE.md:1`, `docs/assignment/BUSINESS-RULES.md:1`, `docs/assignment/CROSS-CHECK.md:1`, `docs/assignment/ERD.md:1`, `docs/item-bank/ARCHITECTURE.md:1`, `docs/item-bank/ERD.md:1`, `docs/item-bank/BUSINESS-RULES.md:1`, `docs/verify/migration-review-1.md:1`, `characterization/tests/item-bank.test.js:1`, `characterization/__snapshots__/item-bank.test.js.snap:1` | 요청 문장("엔드포인트 1개 이관")으로 설명되지 않는 파일. 특히 `docs/assignment/*`(약 1,680줄)는 다른 모듈(assignment) 문서이고, `CLAUDE.md` · `.claude/skills/*` 는 작업 환경 설정이다. 원인은 비교 기준이 `upstream/main` 이라 이관 이전 커밋(`1067935` 이전)까지 포함된 것으로 보인다. `1067935..HEAD` 로 좁히면 이관 커밋 2개(`c7adc01`, `a284d9b`)의 16개 파일(문서 2 · `ItemRepository` · 새 소스 8 · 테스트 5)만 남는다. | 이관 커밋만의 범위(`1067935..HEAD`)로 다시 검증하거나, 무관한 변경을 별도 PR 로 나눈다. 아래 #2 를 해결한 뒤 같은 범위로 재검증하면 승인 가능성이 높다. |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:33` | 레거시는 입력 경고(키워드 한 글자, 단원 코드 형식 오류 등)를 화면에 띄우지만 새 API 는 `ItemSearchCondition.warnings` 를 만들어 놓고 DEBUG 로그(34행)에만 남기고 응답에 싣지 않는다(`ItemSearchResponse.java:12`, 클래스 Javadoc 에도 "경고 문구는 응답에 넣지 않는다"). 동작 보존 테스트의 정규화 결과(`status · rows · count · message`)에 경고가 포함되지 않아 통과하지만, "동작을 바꾸지 않고"라는 요청과는 차이가 있다. | 의도된 생략이면 "의심 동작 · 차이" 목록에 명시해 사람이 확인한다. 보존해야 하면 응답에 `warnings` 필드를 추가하고 정규화 · 스냅샷 범위를 사람과 협의한다(스냅샷은 임의로 고치지 않는다). |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:52` | 페이지 조회(36행)와 상세 조회(50행)가 서로 다른 쿼리라, 그 사이에 문항이 사라지면 `loaded::get` 이 `null` 이 되어 `ItemSearchRow.from` 에서 NPE 가 난다. `readOnly` 트랜잭션이라 가능성은 낮다. | `loaded.get(id)` 가 `null` 이면 건너뛰거나 도메인 예외로 바꾼다. |

## 확인 필요

- 새 API 대상 테스트(8080)는 이미 떠 있던 서버로 실행했다. 이 프로세스가 현재 HEAD(`a284d9b`)로 빌드된 것인지 확인하지 못했다. 검증 확정 전에 `./gradlew bootRun` 으로 재기동한 뒤 `TARGET_BASE_URL=http://localhost:8080 npm test` 를 다시 돌려 주세요.
- 이번 범위의 `characterization/` 파일은 추가만 있고 기존 테스트 · 스냅샷의 수정 · 삭제는 없다(스냅샷을 고쳐 통과시킨 흔적 없음).
