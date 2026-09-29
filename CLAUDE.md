# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

답변과 문서는 한국어로 쓴다.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — Spring Boot 3.3 · Java 21 · Gradle wrapper. 테스트는 H2(MariaDB 모드)라 DB 없이 통과한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'   # 단일 테스트 클래스
cd modern/api && ./gradlew bootRun        # 8080. 먼저 저장소 루트에서 docker compose --profile modern up -d

# modern/web — React 18 · TypeScript · Vite
cd modern/web && npm ci                   # npm install 대신 npm ci (lock 파일을 바꾸지 않는다)
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx            # 단일 테스트 파일
cd modern/web && npm run build
cd modern/web && npm run dev              # 5173
```

- 작업을 보고하기 전에 바꾼 모듈의 테스트 명령을 실행하고 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 쓴다.
- `modern/web` 을 바꿨다면 `lint` · `typecheck` · `test` 세 명령을 모두 실행한다.

## 2. 코딩 컨벤션

### modern/api — 계층
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`)다. 호출 방향은 Controller → Service → Repository → 엔티티.
- 컨트롤러는 서비스만 주입받는다. 컨트롤러에 Repository 주입이나 SQL 문자열이 있으면 위반이다.
- 다른 도메인의 데이터는 그 도메인의 Service 를 거쳐 쓴다. 다른 도메인 패키지의 Repository 를 주입하지 않는다.
- 컨트롤러는 엔티티를 반환하지 않고 DTO 를 반환한다. 엔티티 하나를 DTO 로 바꿀 때는 DTO 의 `static from(엔티티)` 팩토리를 쓴다(예: `ItemResponse.from`).
- 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.

### modern/api — Java 21
- 요청 · 응답 DTO 는 `record` 로 만든다. 엔티티는 JPA 때문에 `class` 로 둔다.
- 스트림 수집은 `Collectors.toList()` 대신 `.toList()` 를 쓴다.
- 타입 검사 뒤 캐스팅은 `instanceof X x` 패턴 매칭으로 쓴다. 값을 고르는 분기는 `switch` 식(`->`)으로 쓴다.
- Lombok 을 쓰지 않는다(의존성에 없다).

### modern/api — setter
- `src/main` 에 public setter(`setXxx`)를 추가하지 않는다. 지금 코드에는 setter 가 0개다.
- 상태 변경은 의도가 드러나는 이름의 메서드로 한다(예: `Distribution.markRedistributed(at)`, `Item.addTag(tag)`).
- setter 가 꼭 필요하다고 판단되면 추가하기 전에 이유를 적어 묻는다.

### modern/api — 예외
- 빈 `catch` 블록을 두지 않는다. 잡았으면 로그를 남기고 다시 던지거나 도메인 예외로 바꿔 던진다.
- 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 에서만 한다. 컨트롤러 메서드 안에서 try-catch 로 상태 코드를 만들지 않는다.
- 매핑: `NotFoundException` 404 · 검증 실패 · 타입 불일치 · `IllegalArgumentException` 400 · `IllegalStateException` 409 · 그 밖의 예외 500. 응답 본문은 `ErrorResponse`.

### modern/api — 로깅
- 로거는 SLF4J 만 쓰고 `private static final Logger log = LoggerFactory.getLogger(<자기 클래스>.class);` 로 선언한다.
- `System.out.println` · `System.err.println` · `e.printStackTrace()` 를 쓰지 않는다.
- 메시지는 `{}` 자리표시자로 값을 넣는다. 문자열을 `+` 로 이어 붙이지 않는다.
- 메시지는 영어 소문자로 시작한다(예: `"redistributed distribution {} (assignment {}, class {}) reason={}"`).
- 예외를 로그에 남길 때는 예외 객체를 마지막 인자로 넘긴다(`log.error("... {}", uri, ex)`).
- 레벨: 조회 결과 건수는 `DEBUG`, 상태 변경 · 정상 처리 요약은 `INFO`, 복구 가능한 실패와 4xx 중 상태 충돌은 `WARN`, 요청을 실패시키는 예상 못 한 예외는 `ERROR`.
- 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 로그에 찍지 않는다.

### modern/api — 이름 · 형식
- 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 약어도 첫 글자만 대문자(`ItemDto`).
- 비즈니스 규칙 값(난이도 상한 등)은 이름 붙인 상수로 둔다(예: `MAX_LEVEL = 5`).
- 들여쓰기 4칸, 한 줄 120자 이내, 와일드카드 import(`.*`) 금지.

### modern/api — 테스트
- 새 public 서비스 메서드 · 새 엔드포인트마다 테스트를 1개 이상 추가한다.
- 컨트롤러는 `@WebMvcTest` + `@MockBean`, 서비스는 Mockito 단위 테스트, 리포지토리 쿼리는 `@DataJpaTest` + `@ActiveProfiles("test")`(H2) 로 검증한다.
- 테스트 메서드 이름은 확인하는 동작이 드러나는 camelCase(예: `rejectsOtherOrigins`)로 짓고 한국어 `@DisplayName` 을 붙인다.
- 테스트가 실제 MariaDB 에 붙게 만들지 않는다. 테스트 설정은 `src/test/resources/application-test.yml` 뿐이다.

### modern/web
- 모든 API 호출은 `src/api/client.ts` 의 `getJson` 을 거친다. 컴포넌트 · 훅에서 `fetch` 를 직접 호출하지 않는다.
- 조회 훅은 `hooks/useApiQuery` 위에 만든다.
- `any` 금지, 미사용 변수는 `_` 접두어만 허용, 타입만 가져올 때는 `import type`(ESLint 가 검사한다).
- 컴포넌트 · 훅 테스트는 `src/test/mockFetch.ts` 로 `fetch` 를 가짜로 바꾼다.

## 3. 금지 사항

- `legacy/` 는 분석 · 이관 대상이며 허락 없이 수정하지 않는다.
- DB 스키마(테이블 · 컬럼 · 인덱스) · 마이그레이션 · 시드 데이터(`db/`) 변경은 먼저 묻는다.
- 의존성 추가(`build.gradle` 의 `dependencies`, `package.json`)는 먼저 묻고 이유와 대안을 함께 적는다.
- `modern/api/src/main/resources/application.yml` 의 DB 접속 정보 · 커넥션 풀 설정을 바꾸지 않는다.
- 실제 비밀값(운영 계정 · 토큰 · 키)을 코드 · 설정 파일에 리터럴로 넣지 않는다. 실습용 더미 값(`app-pass`)은 예외다. 루트 `.env` 를 만들지 않는다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정을 만들지 않는다.
- `@Transactional` 메서드 안에서 외부 HTTP 를 호출하거나 요청 크기에 비례하는 루프를 돌리지 않는다.
- 요청에 없는 파일을 고쳤다면 답변에 파일명과 이유를 적는다. 포맷팅 · import 정리는 요청 범위 안의 파일에서만 한다.
- `characterization/` 테스트나 스냅샷을 깨뜨리는 변경은 먼저 사람에게 알린다. 스냅샷을 고쳐 통과시키지 않는다.

## 4. 아키텍처 안내

### modern/api
```
src/main/java/com/example/
├── item/         문항 · 단원 · 태그 (Item, Unit, Tag, *Controller, *Service, *Repository, *Response)
├── assignment/   과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*, ClassRoom, Submission)
├── common/       GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig, RootController
└── config/       WebConfig (CORS)
```
- 엔드포인트: `GET /`, `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`.
- 공개 문항은 `status = 'A'`(`ItemStatus`)로 판단한다. 삭제 플래그가 아니다. 단원 문항 목록은 `level DESC, id ASC` 순서.
- 서비스가 쓰는 현재 시각은 `ClockConfig` 의 `Clock` 빈을 주입받아 `now(clock)` 으로 얻는다(테스트에서 시각을 고정하기 위해, 예: `DistributionService`). 인자 없는 `now()` 는 `ErrorResponse` 의 응답 시각 한 곳에만 있다.
- CORS: `/api/**` 에 `http://localhost:5173` 의 GET 만 허용한다.
- `application.yml` 의 Hikari 풀은 운영과 같은 값(max 5, connection-timeout 3s, leak-detection 10s)이다. 트랜잭션을 오래 쥐는 코드는 풀을 금방 고갈시킨다.
- JPA `ddl-auto: none`. 스키마 기준은 `db/mariadb/init/01-schema.sql` 이다. 테스트 프로필만 `create-drop`.
- 로컬 DB 는 compose `modern` 프로필의 MariaDB(3306, DB `itembank`)다. tmpfs 라 `down` → `up` 하면 시드 상태로 돌아간다.

### modern/web
- `API_BASE` 는 `VITE_API_BASE`(기본 `http://localhost:8080`)다. 빈 문자열이면 상대 경로를 쓰고 Vite 프록시가 `/api` 를 8080 으로 넘긴다.
- `useApiQuery(key, load)`: key 가 `null` 이면 idle, key 가 바뀌거나 언마운트되면 이전 요청을 `AbortController` 로 취소한다. 상태는 `QueryState` 판별 유니온.
- `ApiError`(2xx 가 아닌 응답)와 네트워크 오류는 `toErrorMessage` 가 한국어 메시지로 바꾼다.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.
