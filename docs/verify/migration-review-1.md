판정: 반려

- 검증 대상: `git diff upstream/main...HEAD` (26개 파일, +6125 / -1)
- 요청 범위: 레거시 엔드포인트 1개(`search.php`)를 동작을 바꾸지 않고 modern 으로 이관.
- 기준: `templates/approval-checklist.md` v0, `CLAUDE.md`

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew cleanTest test` → 54개 중 통과 54 · 실패 0 · 오류 0 · 건너뜀 0. `cd characterization && npm test`(레거시 8081) → 39 통과 · 실패 0. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 38 통과 · 실패 0 · 건너뜀 1(#4 참고, 이번 변경과 무관). item-bank 검색 케이스는 양쪽 모두 전부 통과 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 없음, SQL 은 Specification 으로 바인딩(문자열 이어 붙이기 없음), 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | **미충족** | 요청 문장은 "엔드포인트 1개 이관"인데 변경 파일에 이관과 무관한 `CLAUDE.md`, `.claude/skills/*`, `docs/assignment/*`(다른 모듈 문서 4개, 약 1,680줄)가 들어 있다. #1 |
| 컨벤션 준수 | 충족 | 위반 없음(경고 · 제안만 있음). 계층(Controller → Service → Repository), DTO record, setter 0개, Lombok 없음, SLF4J `{}` · 영어 소문자 메시지, 와일드카드 import 없음, 120자 초과 없음, 테스트마다 `@DisplayName` 확인 |

이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `docs/assignment/ARCHITECTURE.md:1`, `docs/assignment/BUSINESS-RULES.md:1`, `docs/assignment/CROSS-CHECK.md:1`, `docs/assignment/ERD.md:1` | 요청(search.php 이관 1건)으로 설명되지 않는 파일이다. 체크리스트 3번 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다"를 충족하지 못한다. 비교 기준 `upstream/main` 이 이전 단계 작업(문서 · Skill · 초기 설정)보다 앞이라 범위에 딸려 들어왔을 가능성이 크다. 다만 diff 만으로는 구분할 수 없다 | 이관 변경만 따로 낸다(이전 단계 커밋은 별도 PR 로 분리하거나, 이미 머지된 지점을 기준으로 범위를 다시 잡는다). 범위를 고쳐도 같은 파일이 남으면 요청에 포함된 이유를 적는다. 이 항목만 해소되면 나머지 세 기준이 충족이므로 승인 가능 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:39` | 검색 경고를 DEBUG 일 때만 계산한다. `collectWarnings`(57줄)는 단원 · 태그 조회 쿼리 2개를 `if (log.isDebugEnabled())` 안에서만 실행한다. 로그 레벨에 따라 DB 부하와 트랜잭션 내 쿼리 수가 달라지고, 운영(INFO)에서는 실행되지 않는 코드가 된다. 경고는 응답에도 쓰이지 않는다 | 로그용이라면 `collectWarnings` 와 단원 · 태그 repository 주입을 없애고 `condition.warnings()` 만 DEBUG 로 남긴다. BR-09 · BR-11 경고를 응답에 싣기로 했다면 응답 필드로 올린다 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:79` | `ids.stream().map(loaded::get)` 은 1차 쿼리와 2차 쿼리 사이에 문항이 사라지면 `null` 이 되어 `ItemSearchRow.from` 에서 NPE 가 난다. MariaDB 기본 격리(REPEATABLE READ)의 읽기 전용 트랜잭션에서는 실제로 일어나기 어렵다 | 지금 동작은 유지해도 된다. 방어하려면 `Objects::nonNull` 로 걸러 둔다 |
| 4 | 제안 | `characterization/tests/example-units.test.js:21` | 새 API 대상 실행에서 건너뛴 1건이 이 줄의 `it.skipIf(legacyOnly)` 다. 이번 diff 에 들어 있지 않은 기존 예제(`units.php`)이고 item-bank 검색 테스트는 건너뛴 것이 없다. 수정하지 않았다 | `units.php` 이관 시점에 정리한다. 이번 변경에서는 조치 불필요 |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:93` | `toSort()` 가 public 이지만 `pageable()`(89줄) 외에 쓰는 곳이 없다 | `private` 으로 좁히거나 `pageable()` 에 합친다 |

확인 필요
- `localhost:8080` 의 새 API 가 현재 HEAD 로 빌드된 것인지 직접 확인하지 못했다. `/api/items/search` 경로가 응답하고 38건이 통과했으므로 이관 코드가 떠 있는 것은 맞다. 다만 `bootRun` 을 다시 띄운 뒤 `characterization` 을 한 번 더 돌리면 확실하다.
- 코드는 수정하지 않았고, 이 파일만 새로 만들었다(`docs/verify/migration-review-1.md`).
