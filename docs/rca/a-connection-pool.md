# a-connection-pool 장애 원인 분석 (RCA)

## 요약

- **무슨 일**: 2026-09-16 14:37:52 ~ 14:51:49 KST, 학급 리포트 API 가 DB 연결 5개를 쥐고 놓지 않아 풀이 고갈됐다. 사용자에게 보인 장애는 14:40:52 ~ 14:51:47 (약 11분), 5xx 186건(500 49 · 502 82 · 504 55).
- **영향**: 리포트가 아닌 요청(`/api/units/*/items` · `/api/items/*` 등)도 연결을 못 얻어 실패했다(5xx 186건 중 94건).
- **직접 원인**: `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회 후 SHA-256 200,000회 서명 루프를 돌아 연결을 약 16~20초 점유. 리포트 호출이 분당 3건에서 10~19건으로 늘자 풀(5개)이 소진됐다.
- **확신 수준: 중간.** leak 134건 전부가 이 메서드이고 코드 구조도 일치하나, 서명 루프의 실제 소요 시간 · 장애 당시 배포 버전 · 호출 증가 원인은 확인하지 못했다.
- **미해명**: 14:37 `submission` 조회 1,284,310행 검사(9.8초)는 이 원인으로 설명되지 않는다(9절).

## 1. 수집 범위

대상: `incident-logs/a-connection-pool/` (모두 2026-09-16 기록). 이 절은 범위만 정한다. 원인 추측은 하지 않는다.
방법: `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 분 단위 집계만 사용했고 파일을 통째로 읽지 않았다.

### 1.1 파일별 기록 기간 · 줄 수 · 시각 형식

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 | 타임존 |
|---|---|---|---|---|
| `app.log` | 5,371 (타임스탬프 시작 줄 1,927, 나머지 3,444줄은 스택트레이스 등 이어지는 줄) | 06:43:56 ~ 15:29:51 | `2026-09-16T14:37:52.199+09:00` (ISO-8601) | KST (`+09:00` 명시) |
| `nginx-access.log` | 21,086 | 06:43:56 ~ 15:29:59 | `[16/Sep/2026:14:40:52 +0900]` | KST (`+0900` 명시, 21,086줄 전부) |
| `nginx-error.log` | 168 | 09:12:03 ~ 14:51:47 | `2026/09/16 14:41:06` (오프셋 없음) | KST로 판단 (아래 근거) |
| `mariadb-slow.log` | 21 (슬로우 쿼리 3건) | 09:14:09 ~ 14:37:52 (UTC 00:14:09 ~ 05:37:52) | `# Time: 260916  5:37:52` + `SET timestamp=<epoch>` | **UTC** (오프셋 없음) |

`nginx-error.log` 가 KST인 근거: 14:41:06 `upstream timed out` (client B, `GET /api/classes/3/report`) 가 `nginx-access.log` 의 같은 client · URL 504(+0900) 14:41:06 과 초 단위로 일치한다. 504는 access 55건, error 의 `upstream timed out` 55건으로 건수도 같다.

### 1.2 KST 변환 규칙

- `app.log`, `nginx-access.log`: 변환 없음.
- `nginx-error.log`: 변환 없음(KST로 취급).
- `mariadb-slow.log`: **KST = UTC + 9시간**. `# Time` 은 쿼리가 끝난 시각이다(시작 시각 = `SET timestamp` epoch, 끝 시각 = 시작 + `Query_time`). 예: `5:37:52` UTC → 14:37:52 KST (`SET timestamp=1789537062` = 05:37:42 UTC, `Query_time` 9.8초).
- 날짜 경계: 슬로우 로그의 `260916  0:14:09` 는 UTC 기준 날짜이고 KST로는 같은 날 09:14:09 다. 이 로그의 3건은 모두 날짜가 바뀌지 않는다.

### 1.3 분 단위 이상 건수와 이상 구간

집계 기준: `app.log` WARN · ERROR 줄, `nginx-access.log` 5xx, `nginx-error.log` `[warn]` · `[error]`(timed out 포함), `mariadb-slow.log` 전체.

| 파일 | 평소 수준 (14:36 이전) | 달라지는 시작 | 끝 | 구간 내 피크 |
|---|---|---|---|---|
| `app.log` | WARN 시간당 약 1건. 전부 409 `state conflict`(마감된 과제 재배포). ERROR 0건 | 14:37 (HikariCP `ProxyLeakTask` leak 감지 WARN 첫 건 14:37:52.199) | 14:51 (마지막 leak WARN 14:51:37). 14:52 는 409 1건 뿐 | 14:43 에 44건, 14:41 에 31건. 14:40~14:49 는 분당 17~44건 |
| `nginx-access.log` | 5xx 0건 (06:43~14:39) | 14:40 (14:40:52 첫 500) | 14:51 (14:51:47 마지막 504) | 14:45 에 27건, 14:46 에 26건 |
| `nginx-error.log` | `[warn]` 2건 (09:12, 13:05) | 14:41 | 14:51 (14:51:47) | 14:46 에 25건 |
| `mariadb-slow.log` | 3건 중 2건이 이상 구간 밖 (09:14 KST 2.3초 / 11:02 KST 2.0초, Lock 0.9초) | 14:37:52 KST (9.8초, Rows_examined 1,284,310) | - | - |

분 단위 상세(KST, 건수):

| 분 | app WARN/ERROR | access 5xx | nginx-error |
|---|---|---|---|
| 14:37 | 1 | 0 | 0 |
| 14:38 | 2 | 0 | 0 |
| 14:39 | 3 | 0 | 0 |
| 14:40 | 17 | 1 | 0 |
| 14:41 | 31 | 9 | 3 |
| 14:42 | 17 | 20 | 20 |
| 14:43 | 44 | 17 | 8 |
| 14:44 | 17 | 13 | 15 |
| 14:45 | 33 | 27 | 22 |
| 14:46 | 24 | 26 | 25 |
| 14:47 | 24 | 14 | 10 |
| 14:48 | 22 | 15 | 15 |
| 14:49 | 33 | 23 | 20 |
| 14:50 | 12 | 18 | 22 |
| 14:51 | 4 | 3 | 3 |
| 14:52 | 1 (409) | 0 | 0 |

5xx 합계 186건 = 500 49건 + 502 82건 + 504 55건. 이 구간 밖 5xx는 0건이다.

**제안 이상 구간: 14:37 ~ 14:52 KST** (app 첫 이상 14:37:52, 마지막 이상 14:51:47, 14:52 에 평소 수준 복귀 확인).

### 1.4 앞뒤 여유 제안

- 앞쪽 **60분**, 뒤쪽 **15분**.
- 앞쪽 근거: 14:36 이전에는 5xx 0건이고 13:37~14:36 의 WARN 은 409 5건(13:21 · 14:02 · 14:11 · 14:14 · 14:33)뿐이라 이 범위에서 leak 징후는 없었다. 원인 쪽 신호가 증상(14:37)보다 먼저 나타날 수 있어 뒤쪽보다 4배 넓게 둔다.
- 뒤쪽 근거: 14:52 이후 5xx가 없고 app 이 평소 수준으로 돌아왔다. 복구 확인용으로 15분이면 충분하다.
- **수집 범위: 13:37 ~ 15:07 KST** (슬로우 로그 기준 UTC 04:37 ~ 06:07).
- 예외: `mariadb-slow.log` 는 21줄뿐이라 하루 전체를 포함한다. 범위 밖인 09:14 KST(item·unit 조인 조회 2.3초)와 11:02 KST(unit 조회 2.0초, Lock 0.9초)는 이상 구간 이전의 후보 신호로 남겨 두고, 관련성 판단은 다음 절로 미룬다.
- `app.log` · `nginx-access.log` 는 15:07 이후 15:29 까지 기록이 더 있다. 범위 밖이지만 재발 여부 확인이 필요하면 15:29 까지 `grep -c` 로 건수만 확인한다.

### 1.5 한계

- `nginx-access.log` 에는 응답 시간 필드가 없어 지연은 이 로그로 볼 수 없다.
- 4xx(404 1,250건 · 409 20건 · 400 25건)는 이상 구간 판단에서 제외했다. 평소부터 있는 건수다.
- 검증: 타임존 판단과 범위 · 여유 제안은 별도 서브에이전트(adviser)가 독립적으로 `grep` · `awk` 로 재확인했고 위 수치와 일치했다.

## 2. 타임라인

대상: 13:37 ~ 15:07 KST (1절에서 정한 수집 범위). 모든 시각은 KST이고 슬로우 로그는 +9시간 변환했다. 로그에 있는 사실만 적었고 원인 해석은 하지 않는다.

- 출처 표기: `app`=`app.log`, `acc`=`nginx-access.log`, `err`=`nginx-error.log`, `slow`=`mariadb-slow.log` (슬로우 로그는 `# Time` 줄 번호).
- `nginx-access.log` · `nginx-error.log` 는 초 단위, `app.log` 는 밀리초 단위다. 파일 간 차이가 1초 안팎이면 (선후 불확실)로 표시했다.
- "N건/분"은 해당 메시지의 분당 건수 범위다.

### 2.1 사건 타임라인

| 시각 | 출처 (파일:줄) | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 13:51:51 | app:1094 | 평소 이벤트: 과제 재배포 정상 처리 | `redistributed distribution 8 (assignment 6, class 2) reason=STU-…` |
| 14:02:40 | app:1122 | 평소 이벤트: 마감 과제 재배포 409. 이후 14:11:38 (app:1154), 14:14:03 (app:1165), 14:33:45 (app:1229) 에 같은 메시지 (시간당 약 1~2건, 평소 수준) | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=1` |
| 14:04:33 | app:1129 | 평소 이벤트: 재배포 정상 처리. 14:19:38 (app:1182) 에도 1건 | `redistributed distribution 7 (assignment 5, class 3) reason=null` |
| 13:37~14:37 | acc:16189~18785 | 트래픽 변화 없음: 5분당 요청 194~238건, 5xx 0건. `/api/classes/*/report` 호출은 14:30대 구간에도 접근 로그에 없음(바로 앞 기록은 13:50대 2건, 14:10대 5건) | (집계: 줄별 발췌 없음) |
| 14:37:42 → 14:37:52 | slow:15 (`SET timestamp` 14:37:42 KST 시작, `# Time` 14:37:52 종료) | **슬로우 쿼리 1건**: 9.8초, Rows_examined 1,284,310, Rows_sent 4. 이 파일에서 가장 느린 쿼리이고 범위 안 유일한 슬로우 기록 | `select s1_0.id,… from submission s1_0 where s1_0.distribution_id=5 order by…` |
| 14:37:52.199 | app:1243 | **HikariCP 누수 감지 WARN 첫 발생.** 스레드 exec-6, 연결 `6e8f14d3`. 스택에 `ReportService.buildClassReport` ← `ReportController.classReport(ReportController.java:22)` 가 보인다 (선후 불확실: slow:15 와 같은 초) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3…` |
| 14:38:01 | acc:18801 / app:1279~1280 | 같은 시각 `GET /api/classes/3/report` 200 이 접근 로그에 처음 나타나고, app 에는 리포트 생성 INFO(14:38:01.900)와 누수 연결 반환 INFO(14:38:01.902)가 찍힌다 (선후 불확실: acc 는 초 단위) | `built class report for class 3: 3 distributions, 10 submissions` / `…was returned to the pool (unleaked)` |
| 14:38:20 | app:1261 | 누수 WARN 반복 시작: 14:37 1건 → 14:38 2건 → 14:39 3건/분 (연결 `6e8f14d3`, `2ac19f07`, `71d4b8e2` 순으로 등장) | `Connection leak detection triggered for … on thread http-nio-8080-exec-3` |
| 14:38:29 | acc:18817 / app:1278 | 과제 재배포 POST 200 (distribution 5, class 3). 슬로우 쿼리의 `distribution_id=5` 와 같은 번호 | `POST /api/distributions/5/redistribute HTTP/1.1" 200 170` |
| 14:38:01~14:39 | acc:18801~18882 | `GET /api/classes/{1,3}/report` 가 약 30초 간격으로 200 응답 (분당 1~3건, 접근 로그에서 직전 구간 대비 증가 시작) | `GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:40 | acc / app | `/api/classes/*/report` 호출 10분 구간 집계: 14:30대 6건 → 14:40대 150건 (14:50대 17건, 15:00대 2건). 리포트 생성 INFO 도 분당 3건(14:38~39) → 8~15건/분(14:40~14:51) | `built class report for class 1: 3 distributions, 10 submissions` |
| 14:40:52.319 | app:1522 | **풀 고갈 ERROR 첫 발생** (total=5, active=5, idle=0, waiting=4). 이후 14:49 까지 49건 (14:40 1 · 14:41 6 · 14:43 10 · 14:45 8 건 등 분당 1~10건). 같은 시각 `unhandled exception on /api/units/M6-2/items` (app:1523) | `Connection is not available, request timed out after 3000ms (total=5, a…` |
| 14:40:52 | acc:18918 | **5xx 첫 발생**: `GET /api/units/M6-2/items` 500 (선후 불확실: app:1522 와 같은 초). 이후 14:51 까지 186건 (500 49 · 502 82 · 504 55), 분당 1~27건 | `GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| 14:40 | app (누수 WARN) | 누수 WARN 분당 건수 급증: 14:39 3건 → 14:40 14건/분. 14:40~14:50 은 9~14건/분, 5개 연결 ID(`6e8f14d3` 외)가 반복 | `Connection leak detection triggered for … on thread http-nio-8080-exec-6` |
| 14:41:06 | err:6 | **nginx 오류 첫 발생**: upstream 응답 대기 타임아웃. 14:51 까지 55건 (분당 1~9건대, 총 55건 = acc 504 55건과 같은 건수). 동일 초 acc:18933 `/api/classes/3/report` 504 (선후 불확실) | `upstream timed out (110: Connection timed out) while reading response header…` |
| 14:42:01 | acc:18970 | 502 첫 발생 (`GET /api/classes/2/report`). 502 는 이후 14:51 까지 82건. (14:41:17 acc:18942 는 `/api/items/30` 500) | `GET /api/classes/2/report HTTP/1.1" 502` |
| 14:42:01 | err:10 | nginx 경고: upstream 일시 비활성화. 14:50 까지 26건 | `upstream server temporarily disabled while reading response header from up…` |
| 14:42:01 | err:11 | nginx 오류: upstream 접속 거부. 14:50 까지 14건 | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:04 | err:12 | nginx 오류: 사용 가능한 upstream 없음. 14:51:02 (err:166) 까지 68건 | `no live upstreams while connecting to upstream, client: E…` |
| 14:44:19 | app (DistributionService) | 재배포 정상 처리 INFO (distribution 8, class 2). 이상 구간 중에도 일부 요청은 정상 처리 | `redistributed distribution 8 (assignment 6, class 2) reason=…` |
| 14:49:51 | app:4981 | 마지막 `unhandled exception` ERROR (`/api/units/M5-3/items`). 풀 고갈 ERROR 는 이 직전 14:49 가 마지막 (이후 0건) | `unhandled exception on /api/units/M5-3/items` |
| 14:51:16~14:51:37 | app:5215, 5230, 5251 | 마지막 누수 WARN 3건 (14:51 구간 3건/분). 이후 0건 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3…` |
| 14:51:47 | acc:19381 / err:168 | **마지막 5xx(504)** 와 마지막 nginx 오류 (`/api/classes/3/report`). 같은 초 (선후 불확실) | `upstream timed out (110: Connection timed out) while reading response header…` |
| 14:51:49 | app:5266~5267 | 마지막 누수 연결 반환 INFO (`6e8f14d3`) 와 직전 리포트 생성 INFO | `Previously reported leaked connection … was returned to the pool (unleaked)` |
| 14:51:52 | app:5268 | 평소 이벤트 복귀: 409 `state conflict` 재배포. 14:52:09 (app:5269) 에도 1건. 이후 5xx · 누수 · 풀 고갈 로그 없음 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=2` |
| 14:59:15 | app:5283 | 이상 구간 이후 리포트 생성 INFO 재개 (14:59:15 · 14:59:52 · 15:01:20, 분당 0~1건). 접근 로그 `/api/classes/*/report` 도 15:00대 2건으로 평소 수준, 5xx 0건 | `built class report for class 3: 3 distributions, 10 submissions` |
| 15:07 | app:5305 / acc:20064 | 수집 범위 끝. 15:07:55 까지 이상 로그 없음 | (끝 줄) |

### 2.2 범위 안의 반복 · 잡음 메시지 (이상 구간 전후 동일)

범위 안에서 건수만 집계했고, 이상 구간(14:37~14:52) 이전에도 같은 빈도로 나오는 항목이다.

- `GlobalExceptionHandler` INFO `no handler for /favicon.ico` · `/robots.txt` · `/api/unit` · `/apple-touch-icon.png` · `/api/item/12`: 하루 내내 산발적으로 나온다.
- 404 `not found: 문항이 없습니다` · `배포가 없습니다` · `단원이 없습니다`, 400 `bad request: Method parameter 'id'…` INFO: 평소부터 있던 4xx.

### 2.3 이상 항목 점검 결과

가장 많이 나온 오류(풀 고갈 · 5xx) 보다 앞선 시각(13:37~14:40:51)에서 평소와 다른 것을 찾은 결과다.

| 항목 | 결과 |
|---|---|
| 느린 쿼리 | 14:37:42~14:37:52 `submission` 조회 9.8초 1건 (slow:15). 범위 안 유일 |
| 경고 | 14:37:52 누수 WARN 이 5xx(14:40:52) 보다 3분, 풀 고갈 ERROR 보다 3분 앞선 첫 이상 로그 (app:1243). 그 이전 WARN 은 409 뿐 |
| 설정 재적재 · 재시작 | 없음. app · nginx 로그 모두 `reload` · `restart` · `shutdown` · `started` 류 줄 없음 (grep 확인) |
| 트래픽 변화 | 전체 요청량은 5분당 194~238건으로 변화 없음. `/api/classes/*/report` 호출만 14:38 부터 증가, 14:40대 10분 구간 150건 (평소 10분당 0~6건) |
| nginx 오류 로그 | 13:37~14:41:05 구간 기록 없음 (이전 기록은 09:12 · 10:42 · 11:47 · 13:05, 모두 범위 밖) |
| 상태 변경 호출 | 재배포 POST 정상 처리가 13:51 · 14:04 · 14:19 · 14:38:29 · 14:44 에 있음 (원인 연결은 하지 않음) |

### 2.4 한계

- `nginx-access.log` 에는 응답 시간이 없어 요청 시작 시각을 알 수 없다. 접근 로그 시각은 응답 기록 시각으로 보이나 로그에 명시되어 있지 않다.
- 슬로우 로그의 시작 시각은 `SET timestamp` 에서 구했다.

## 3. 가설과 검증

이 절은 가설만 세운다. 어느 것이 맞는지는 판정하지 않는다. 줄번호는 이 문서(`docs/rca/a-connection-pool.md`)의 줄이고, 로그 경로는 `incident-logs/a-connection-pool/` 기준이다.

**증상과 원인을 구분한다.** `Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` (100행)는 풀이 비었다는 **증상**이다. 가장 많이 나온 오류이지만 "왜 5개 연결이 14:40 부터 반납되지 않았는가"에 답하지 않는다. 아래 가설은 모두 그 질문에 대한 답이다.

### 가설 1 — 애플리케이션 코드 계층

| 항목 | 내용 |
|---|---|
| 가설 | 학급 리포트 생성(`ReportService.buildClassReport`)이 DB 조회를 끝낸 뒤에도 트랜잭션(커넥션)을 쥔 채 오래 실행되어, 리포트 호출이 늘자 풀 5개를 리포트 요청이 점유했다. 무관한 `/api/units/*/items` 요청이 풀을 못 얻어 500 이 났다. |
| 지지 근거 | 94행: 첫 leak WARN 의 스택에 `ReportService.buildClassReport` ← `ReportController.classReport`. 95행: 리포트 생성 INFO 직후 누수 연결이 `unleaked` 로 반납됨. 100행: 풀 고갈 때 피해를 본 요청은 `/api/units/M6-2/items`(다른 엔드포인트). 110~112행: 마지막 누수 연결 반환과 마지막 5xx 가 같은 시각(14:51:49). |
| 반증 조건 | (a) leak WARN 스택의 상당수가 `buildClassReport` 가 아닌 다른 코드 경로이다. (b) `buildClassReport` 의 DB 작업 이후 구간이 짧아(수십 ms) 연결 점유 시간이 3초 미만이다. (c) 해당 메서드가 장애 시점의 배포 버전에 `@Transactional` 이 없었다. |
| 확인 방법 | `grep -A12 'leak detection triggered' incident-logs/a-connection-pool/app.log \| grep -c buildClassReport` 와 전체 leak WARN 건수 비교. leak WARN 의 `apparent connection leak detected` 시간(점유 시간) 분포 확인. `modern/api/src/main/java/com/example/assignment/ReportService.java:41` 의 `@Transactional` 범위와 `sign()` 반복 횟수(`SIGNATURE_ROUNDS`)를 열어 DB 작업 이후 구간의 길이를 확인한다. |
| 실행 결과 | `grep -c 'Connection leak detection triggered' app.log` → **134**. WARN 블록별 스택 분류(WARN 줄 ~ 다음 타임스탬프 줄 사이에 `buildClassReport` 포함 여부) → **report 134 / other 0**. `grep -c 'was returned to the pool' app.log` → 134 (leak 134건 전부 이후 반납됨). `ReportService.java:23` `SIGNATURE_ROUNDS = 200_000`, `:41` `@Transactional` 안에서 `sign()` 호출. leak 연결의 점유 시간(10초 임계 + 경고 후 반납까지): 3.1 표본 6건에서 경고 후 5.6~9.7초 더 점유(총 약 16~20초) |
| **판정** | **유지** |
| 판정 이유 | leak 134건 전부가 `buildClassReport` 에서 시작했고(반증 조건 a 불충족), 점유 시간이 임계 10초를 넘어(b 불충족), 서명 루프가 트랜잭션 안에 있다(c 불충족). |
| 추가로 필요한 로그 | (점유 전체 분포는 계산하지 못함: 반납 INFO 줄에 점유 시간이 없어 경고 시각으로만 추정. 정확한 점유 시간·DB 시간 대 서명 시간 분리는 메서드 단위 타이밍 로그나 JFR/APM 트레이스가 있어야 한다.) |

### 가설 2 — DB · 쿼리 계층

| 항목 | 내용 |
|---|---|
| 가설 | `submission` 조회가 인덱스를 타지 못하거나 테이블이 커져 느려졌고(1,284,310 행 검사), 느린 쿼리가 커넥션을 오래 잡아 풀이 마른다. |
| 지지 근거 | 93행: 14:37:42~52 `submission where distribution_id=5` 9.8초, Rows_examined 1,284,310 / Rows_sent 4 (검사 대비 반환이 극단적으로 적음). 94행: 첫 leak WARN 이 같은 초(14:37:52). 97행: 같은 `distribution_id=5` 의 재배포 POST 가 14:38:29 에 있음(상태 변경 직후 통계·데이터 변화 가능성). 68행: 이전에도 2.0~2.3초 슬로우 쿼리 2건 있음. |
| 반증 조건 | (a) 슬로우 로그가 3건뿐이고 이상 구간 중(14:38~14:51)에는 추가 슬로우 쿼리가 없다(130행: 범위 내 유일 1건). 즉 풀 고갈이 지속된 13분간 DB 쪽 지연 근거가 없다. (b) `EXPLAIN` 결과 `idx_submission_distribution` 를 쓰고 검사 행 수가 수 건이다. (c) 같은 쿼리를 장애 후 같은 데이터로 실행하면 즉시 끝난다. |
| 확인 방법 | 운영과 같은 스키마에서 `EXPLAIN SELECT ... FROM submission WHERE distribution_id=5 ORDER BY student_id;` 및 `SHOW CREATE TABLE submission;` 로 인덱스 존재 확인(`db/mariadb/init/01-schema.sql:112` 에는 `idx_submission_distribution` 가 있으나 운영 스키마와 같은지는 별도 확인). `SELECT COUNT(*) FROM submission;`, `SHOW TABLE STATUS LIKE 'submission';`. `awk` 로 `mariadb-slow.log` 의 `Query_time` 전체 나열. |
| 실행 결과 | `awk '/Query_time/' mariadb-slow.log` → 2.318446(Rows_examined 41,822) · 2.041903(5) · **9.812417(1,284,310)**. 장애 구간(14:38~14:51) 슬로우 쿼리 **0건**(이상 구간 중 슬로우 로그 기록은 14:37:52 의 1건이 마지막). `EXPLAIN` · `SHOW CREATE TABLE` 은 DB 접속 불가로 실행하지 못함 |
| **판정** | **기각** (지속 원인으로는). 인덱스 문제 여부는 판단 불가 |
| 판정 이유 | 14:37 의 1건 외에는 13분간 슬로우 쿼리가 없어 풀 고갈 지속을 설명하지 못한다(반증 a 해당). 단 1.28M 행 검사 자체는 미해명이므로 별도 의심 항목으로 남긴다. |
| 추가로 필요한 로그 | 운영 DB 의 `EXPLAIN` · `SHOW CREATE TABLE submission` · `SHOW TABLE STATUS`, `long_query_time` 설정값(2초 미만 쿼리는 이 로그에 안 남음), general log 또는 performance_schema 의 쿼리 시간 이력. |

### 가설 3 — 트래픽 · 인프라 계층

| 항목 | 내용 |
|---|---|
| 가설 | 14:38 부터 특정 클라이언트(스크립트·재시도·대시보드 자동 갱신 등)가 `/api/classes/*/report` 를 평소의 수십 배로 호출했고, 이 동시성만으로 풀 5개가 소진됐다. nginx 의 upstream 비활성화(`max_fails`)가 502 를 증폭했다. |
| 지지 근거 | 98~99행: 리포트 호출 10분당 6건(14:30대) → 150건(14:40대) → 17건(14:50대) → 2건(15:00대). 133행: 전체 요청량은 그대로(5분당 194~238건)라 증가분이 리포트에 한정됨. 105~107행: nginx 가 upstream 을 일시 비활성화하고 `no live upstreams` 가 68건 → 502 82건. 92행: 직전 30분은 리포트 호출이 거의 없었다. |
| 반증 조건 | (a) 14:38~14:50 리포트 요청의 클라이언트 IP · User-Agent 가 평소 사용자와 같고 증가가 없거나 완만하다. (b) 평소 호출량(10분당 6건) 수준에서도 같은 코드가 연결을 점유한다는 증거가 있다(트래픽이 방아쇠일 뿐 원인이 아니라는 의미이며, 이 경우 가설 1 이 우세). (c) 리포트 호출 증가가 장애 **이후**에 발생했다(재시도의 결과). |
| 확인 방법 | `awk '$4 ~ /16\/Sep\/2026:14:(3[89]\|4)/ && $7 ~ /report/ {print $1}' incident-logs/a-connection-pool/nginx-access.log \| sort \| uniq -c \| sort -rn` 로 클라이언트별 건수. 같은 조건으로 User-Agent 필드와 분 단위 첫 증가 시각(14:38:01 이전 호출 유무) 확인. `nginx.conf` 의 `proxy_next_upstream` · `max_fails` · `fail_timeout` 설정 파일 확인. |
| 실행 결과 | `awk '$4 ~ /…14:(3[89]\|4[0-9]\|50)/ && $7 ~ /report/ {print $1}' nginx-access.log \| sort \| uniq -c` → 클라이언트 A **67** · B **38** · C **32** · D **29**(합 166). 분당 report 호출: 14:36 이전 → 14:38 **3** · 14:39 **3** · 14:40 **10** · 14:41 **16** … 14:46 **19** · 14:50 **10** · 14:51 **5**. 14:38~14:51 report 응답: 200 **79** · 500 **5** · 502 **32** · 504 **55**. 14:38 이후 첫 5xx 는 14:40:52(report 호출이 이미 분당 10건). 하루 중 14:38 이전 report 호출 A 19 · B 16 · C 17 건(D 는 14:40:43 첫 report 호출). UA 2종(Safari/17.6 59 · Firefox/130 91, 브라우저). nginx 설정 파일은 저장소에 없음 |
| **판정** | **유지** (방아쇠로서). nginx 증폭 부분은 판단 불가 |
| 판정 이유 | 호출 증가(분당 3→10~19)가 첫 5xx(14:40:52)보다 먼저 시작됐고 클라이언트가 내부 IP 4개 · 브라우저 UA 이므로 오류 재시도 폭주 가설(반증 c)은 지지되지 않는다. 증가 원인(사용자 행동·화면 자동 갱신)은 로그에 없다. |
| 추가로 필요한 로그 | 접근 로그의 응답 시간 · Referer · 요청 시작 시각, 프론트엔드 호출 로그(자동 갱신 주기). nginx 증폭은 `nginx.conf`(`max_fails` · `proxy_next_upstream`)와 upstream 상태 변경 로그가 있어야 확인 가능. 가설 1 과 함께 성립한다(평소 호출량에서는 장애가 없었음 → 코드 취약점이 있는 상태에서 방아쇠 역할). |

### 가설 4 — 배포 · 설정 변경 계층

| 항목 | 내용 |
|---|---|
| 가설 | 장애 직전 배포나 설정 변경(코드 버전, 풀 크기·타임아웃, `@Transactional` 범위, 서버 대수)이 있었고, 그 변경이 풀을 감당할 수 없게 만들었다. 설정은 `maximum-pool-size 5` · `connection-timeout 3s` 로 여유가 작다. |
| 지지 근거 | 100행: `total=5` 로 풀이 5개뿐이라 한 요청 유형이 다 점유 가능. 94행: 첫 leak 가 14:37:52 로 갑자기 시작(그 이전 13:37~14:36 에는 leak 징후 없음, 65행 · 131행). 이 로그만으로는 배포 흔적을 확인할 수 없다는 점이 가설을 남겨 둔 이유이기도 하다. |
| 반증 조건 | (a) 13:37~14:37 사이에 배포 · 설정 변경 이력이 없고 `application.yml` 풀 설정이 장애 전후로 동일하다. (b) 같은 버전이 며칠째 돌고 있었고 같은 코드가 이전에 정상 처리했다. 132행에서 로그상 reload · restart 는 이미 없는 것으로 확인됨 → 이 가설은 로그 밖(배포 이력)에서 확인해야 한다. |
| 확인 방법 | `git log --since='2026-09-14' --format='%h %ad %s' --date=iso -- modern/api/src/main/java/com/example/assignment/ReportService.java modern/api/src/main/resources/application.yml`. 배포 파이프라인 이력 · 릴리스 시각(수집 필요). `grep -n 'Started\|Hikari.*Start\|pool-name' incident-logs/a-connection-pool/app.log` 로 프로세스 시작 시각. 직전 이틀 같은 시간대의 `/api/classes/*/report` 호출 수와 비교. |
| 실행 결과 | `git log --since=2026-09-14 -- ReportService.java application.yml` → 1건: `1260542 2026-09-22 실습 저장소 초기 구성`(장애일 9/16 이후). `grep -n 'Started\|HikariPool-1 - Start\|itembank-pool - Start' app.log` → **0건**(기록 기간 내 기동 로그 없음). 2.3절: reload · restart · shutdown 줄 없음. 풀 설정은 `application.yml` 의 `maximum-pool-size 5` · `connection-timeout 3000` · `leak-detection-threshold 10000` 이나 장애 시점의 값인지는 확인 불가 |
| **판정** | **판단 불가(로그 부족)** |
| 판정 이유 | 저장소 이력에 장애 시점 배포 기록이 없고(최초 커밋이 장애 6일 후), app 로그에 기동 로그도 없다. 재시작 없는 변경(설정 핫 리로드, 무중단 배포)은 이 로그로 배제할 수 없다. |
| 추가로 필요한 로그 | 장애 당일 CI/CD 배포 이력 · 컨테이너(Pod) 기동 이력, 장애 직전 `application.yml`/환경변수 변경 이력, 직전 2~3일 동일 시간대 로그(report 호출량 · leak 유무 비교), nginx 설정 변경 이력. |

### 가설 간 관계 메모 (판정 아님)

- 가설 1(코드)과 2(쿼리)는 "연결을 오래 쥐는 주체"가 다르다. 슬로우 쿼리가 1건뿐이면 2 의 설명력이 약해지고, leak 스택이 `buildClassReport` 에 몰려 있으면 1 의 설명력이 커진다. 실제로는 leak 134건 전부가 `buildClassReport` 였고 장애 중 슬로우 쿼리가 없었다.
- 가설 3 과 4 는 "방아쇠"를 설명한다. 1 · 2 와 함께 성립할 수 있다.
- 판정 요약: 가설 1 **유지**, 가설 2 **기각**(지속 원인으로는), 가설 3 **유지**(방아쇠), 가설 4 **판단 불가**. 각 표에 실행 결과 · 판정 · 이유 · 추가 필요 로그 행을 추가했다(표가 항목/내용 세로 구조라 열 대신 행으로 붙였다).
- 판정은 "원인 확정"이 아니라 가설별 반증 조건 점검 결과다. 확정에는 DB 시간 대 서명 시간을 분리하는 타이밍 정보(점유 시간 로그)가 더 필요하다.

### 3.1 실행 기록 (명령과 결과)

모두 `incident-logs/a-connection-pool/` 에서 실행했다.

```text
$ grep -c 'Connection leak detection triggered' app.log
134
$ grep -c 'was returned to the pool' app.log
134
$ awk '<WARN 블록 분류: 블록 안에 buildClassReport 가 있으면 report>' app.log | sort | uniq -c
    134 report
$ grep -h 'at com.example' app.log | awk '{print $2}' | sort | uniq -c | sort -rn | head
    139 ReportService$$SpringCGLIB$$0.buildClassReport(<generated>)
    139 ReportController.classReport(ReportController.java:22)
     19 ItemService$$SpringCGLIB$$0.listActiveItemsByUnit / ItemController.listItemsByUnit
     14 UnitService.listUnits / UnitController.listUnits
      7 ItemService.getItem / ItemController.getItem
      4 DistributionService.getDistribution / DistributionController.getDistribution
$ grep -n 'SIGNATURE_ROUNDS' ReportService.java
23:    static final int SIGNATURE_ROUNDS = 200_000;
$ grep -n 'Query_time' mariadb-slow.log
4:# Query_time: 2.318446  Lock_time: 0.000071  Rows_sent: 7  Rows_examined: 41822
11:# Query_time: 2.041903  Lock_time: 0.912004  Rows_sent: 5  Rows_examined: 5
18:# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310
$ awk '$9>=500{split($7,a,"/"); print a[3]}' nginx-access.log | sort | uniq -c
     92 classes
     57 units
     20 items
     13 distributions
      1 item
      3 (경로 없음)
$ awk '… 14:(3[89]|4[0-9]|50|51) … /report/ {print $9}' nginx-access.log | sort | uniq -c
     79 200
      5 500
     32 502
     55 504
$ git log --since='2026-09-14' --format='%h %ad %s' -- ReportService.java application.yml
1260542 2026-09-22 10:40:42 +0900 실습 저장소 초기 구성 …
$ grep -n 'Started\|HikariPool-1 - Start\|itembank-pool - Start' app.log
(출력 없음)
```

- `at com.example` 집계의 139 는 leak WARN 134건보다 5건 많다(차이 5건의 출처는 확인하지 않음). `units`·`items`·`distributions` 스택(44건)은 풀 고갈 ERROR(`unhandled exception`)의 스택으로, leak 이 아니라 **피해 요청**이다.
- 5xx 186건 중 report 가 92건이고 나머지 94건(units 57 · items 20 · distributions 13 · 기타)은 report 가 아닌 요청이다. report 를 쓰지 않는 사용자도 오류를 겪었다.
- 점유 시간 표본(경고 시각 → 반납 INFO): 14:37:52.199 → 14:38:01.902(9.7초), 14:38:20.016 → 14:38:27.074(7.1초), 14:38:51.641 → 14:38:57.714(6.1초), 14:39:18.720 → 14:39:24.424(5.7초), 14:39:48.603 → 14:39:54.238(5.6초), 14:40:04.730 → 14:40:11.546(6.8초). 경고는 획득 후 10초(`leak-detection-threshold: 10000`)에 발생하므로 총 점유는 약 16~20초다. 전체 134건 분포는 반납 줄 형식 때문에 자동 집계하지 못했다.
- 실행하지 못한 확인: `EXPLAIN` · `SHOW CREATE TABLE`(DB 접속 없음), nginx 설정 확인(파일 없음).

### 3.2 판정

유지된 가설(1 · 3)과 판단 불가였던 가설(4), 기각한 가설(2)을 `modern/api` 의 코드 · 설정과 대조했다. 별도 실행(벤치마크 · 테스트)은 하지 않았고 파일에서 읽은 값만 썼다. 로그에서 단서로 쓴 것은 스택의 `ReportService$$SpringCGLIB$$0.buildClassReport` · `ReportController.classReport(ReportController.java:22)` · `JpaTransactionManager.doBegin` · `TransactionInterceptor.invoke`(app.log:1243~1257), 풀 고갈 메시지의 `total=5` · `3000ms`, 그리고 leak WARN 자체(`ProxyLeakTask`)다.

#### 3.2.1 가설별 코드 · 설정 대조

| 가설 | 대조 결과 | 근거 (파일:줄) |
|---|---|---|
| 1. 리포트가 트랜잭션을 쥠 | **지지한다** | `ReportService.java:41` `@Transactional`(readOnly 아님)이 `buildClassReport` 전체를 감싼다. DB 조회는 `:43` · `:46` · `:54`, 조회가 끝난 뒤 `:71` `sign()` 이 같은 트랜잭션 안에서 실행된다. `sign()` 은 SHA-256 을 `SIGNATURE_ROUNDS = 200_000`(`:23`)회 반복한다(`:88`~`:91`). 코드 주석도 같은 문제를 적고 있다: `:69`~`:70` "DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다". 로그 스택의 `TransactionInterceptor.invoke` → `JpaTransactionManager.doBegin` → `HikariDataSource.getConnection`(app.log:1249~1251)은 `@Transactional` 프록시가 메서드 진입 시 연결을 잡는 경로와 일치한다. `application.yml:22` `open-in-view: false` 라 연결은 트랜잭션 경계에서만 잡힌다. |
| 2. `submission` 쿼리 | **반박한다 (약하게)** | 쿼리는 `SubmissionRepository.java:8` `findByDistributionIdOrderByStudentIdAsc` 와 일치한다. 스키마에는 `db/mariadb/init/01-schema.sql:112` `KEY idx_submission_distribution (distribution_id)` 가 있어 이 조건은 인덱스를 탈 수 있다. 코드만으로는 1,284,310행 검사(slow log)가 설명되지 않는다. 운영 스키마가 저장소와 같은지는 파일로 확인할 수 없다. |
| 3. 트래픽 | **관련 설정을 찾지 못했다 (보호 장치 없음)** | `ReportController.java:19`~`:23` 에 호출 제한 · 캐시가 없다. `modern/api/src/main` 에서 `RateLimit` · `Bucket4j` · `@Cacheable` · `@Async` · `@Scheduled` 검색 결과가 없다. 즉 호출 증가를 막을 코드는 없지만 호출 증가의 **원인**(누가 왜 늘렸는지)은 코드에 없다. nginx 설정 파일도 저장소에 없다. |
| 4. 배포 · 설정 변경 | **관련 코드를 찾지 못했다** | 장애 당시 버전·설정을 알려 주는 파일이 없다. `application.yml` 과 `ReportService.java` 의 git 이력은 `1260542`(9/22, 장애 6일 후) 1건뿐이다. `docker-compose.yml:61`~`:63` 은 DB 접속 정보 환경변수만 지정하고 풀 설정 · 기동 옵션 변경은 없다. |

설정 값(`application.yml:13`~`:18`): `minimum-idle: 1` · `maximum-pool-size: 5` · `connection-timeout: 3000` · `idle-timeout: 600000` · `max-lifetime: 1800000` · `leak-detection-threshold: 10000`. 로그와 일치하는 점: 풀 고갈 메시지의 `total=5`, `request timed out after 3000ms`(100행), leak WARN 이 획득 10초 후에 찍힌다는 점(14:37:42 획득 → 14:37:52 WARN 첫 건, 1.3절 · 93~94행)이다. 풀이 5개이고 대기 3초라 오래 쥐는 요청이 몇 개만 겹쳐도 다른 요청이 3초 안에 연결을 못 얻는다. 다른 서비스는 `@Transactional(readOnly = true)`(`ItemService.java:14` · `UnitService.java:10` · `ItemSearchService.java:17` · `DistributionService.java:30`, `:36`)라 짧게 쓰고 반납하는 구조이고, 피해 요청(`/api/units/*/items` · `/api/items/*` · `/api/distributions/*`)이 그 서비스들이다(3.1 의 `at com.example` 집계).

동시성 대략 계산(로그 수치만 사용, 실측 아님): 점유 시간 표본이 약 16~20초(3.1)이고 report 호출이 분당 10~19건(14:40~14:50)이면, 동시에 열려 있는 report 연결은 대략 10÷60×16 ≈ 2.7 에서 19÷60×20 ≈ 6.3개 범위다. 풀 크기 5 와 같은 규모이므로 고갈이 14:40 부터 시작한 것과 모순되지 않는다.

#### 3.2.2 채택 가설과 기각 사유

**채택: 가설 1 — `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회 후에도 서명 루프(`sign()`)를 돌려 연결을 오래 쥐었고, 리포트 호출이 늘자 풀 5개가 소진되어 다른 API 가 연결을 못 얻었다.**

| 가설 | 판정 | 사유 |
|---|---|---|
| 2. DB · 쿼리 | **기각** | 장애 중(14:38~14:51) 슬로우 쿼리 0건이고(3.1), 풀 고갈이 13분 지속된 것을 설명하지 못한다. 14:37 의 9.8초 쿼리는 첫 점유를 길게 했을 수 있으나 이후 점유 원인은 아니다. 코드상 쿼리는 인덱스가 있는 조건(`01-schema.sql:112`)이라 1.28M행 검사는 별개 의문이다(아래 남은 불확실성). |
| 3. 트래픽 | **원인으로는 기각, 방아쇠로 유지** | 같은 코드가 평소 호출량에서는 하루 종일 문제없이 돌았다(app.log:103 · 106 · 141 등 07~08시대 리포트 정상 생성). 호출이 분당 3건에서 10~19건으로 늘면서 풀이 마른 것이므로 증가는 방아쇠이고, 연결을 오래 쥐는 코드가 없었다면 같은 호출량에서 고갈되지 않았을 것이다. 호출 제한이 없는 것(`ReportController.java:19`~`:23`)은 보조 요인이다. 호출 증가 원인은 로그 · 코드에 없다. |
| 4. 배포 · 설정 | **기각하지 않음 (판단 불가 유지)** | 반박 근거도 지지 근거도 코드 · 설정에서 찾지 못했다. 로그에 기동 · 재적재 흔적이 없다는 정도(2.3절)만 약한 반박이다. |

**확신 수준: 중간.**

- 높이는 근거: leak WARN 134건이 모두 `buildClassReport` 에서 시작했고(3.1), 코드가 트랜잭션 안에서 DB 작업 뒤 서명 루프를 도는 구조를 그대로 보여 주며(`ReportService.java:41` · `:71`), 풀 설정(`application.yml:14` · `:15`)이 로그의 `total=5` · `3000ms` 와 일치한다. 첫 사건에서도 슬로우 쿼리 종료(14:37:52)와 연결 반납(14:38:01.902) 사이에 약 10초가 비는데, 이 구간에 남는 일은 서명 루프뿐이다. 이 해석은 슬로우 쿼리와 같은 스레드라는 가정이 필요하다(둘 다 distribution 5 · class 3 이므로 개연성은 높지만 스레드 ID 는 슬로우 로그에 없다).
- 낮추는 근거 1: 현재 저장소의 코드가 장애 당시 배포된 코드와 같은지 증명되지 않는다(git 이력이 9/22 초기 커밋 1건뿐). 코드 주석이 TODO 로 남은 것으로 보아 같을 가능성이 높지만 확인한 사실은 아니다.
- 낮추는 근거 2: `sign()` 이 몇 초 걸리는지는 측정하지 않았다(벤치마크 금지). SHA-256 200,000회가 16~20초의 대부분을 차지하는지, 연결 경합·CPU 포화로 늘어난 것인지 분리하지 못했다. 서명 시간이 짧다면 점유 시간의 설명이 부족하다.
- 낮추는 근거 3: 가설 4(배포 · 설정 변경)를 로그 · 코드로 배제하지 못했다.

**남은 불확실성 (판정과 별개):**
1. 14:37 의 `submission` 조회 1,284,310행 검사 · 9.8초는 설명되지 않는다. 운영 `EXPLAIN` · `SHOW CREATE TABLE submission` 이 필요하다.
2. 리포트 호출이 14:38 부터 늘어난 이유(사용자 행동인지 화면 자동 갱신인지)는 확인되지 않는다.
3. nginx 가 502 를 증폭했는지(`max_fails` 등)는 설정 파일이 없어 확인하지 못했다.
4. 이 문서는 원인 판정까지만 다룬다. 서명 루프를 트랜잭션 밖으로 빼는 수정은 이 작업 범위에 넣지 않았다(코드 변경 없음).

## 4. 원인

### 4.1 직접 원인

`buildClassReport` 가 DB 작업이 끝난 뒤에도 트랜잭션(= DB 연결)을 쥔 채 CPU 작업을 했다.

### 4.2 인과 순서

1. 14:38 부터 학급 리포트 호출이 분당 3건 → 10~19건으로 늘었다(방아쇠. 원인은 로그에 없음).
2. `buildClassReport` 는 `@Transactional` 로 진입 시 연결을 잡고, 조회를 끝낸 뒤에도 `sign()` 의 SHA-256 200,000회 루프를 같은 트랜잭션 안에서 돌렸다.
3. 그 결과 리포트 1건이 연결을 약 16~20초 점유했다(leak 경고 임계 10초 + 경고 후 5.6~9.7초).
4. 동시 점유가 대략 3~6개가 되어 풀 크기 5 와 같아졌다.
5. 풀이 비자 리포트가 아닌 요청이 3초 대기 후 `Connection is not available` 로 실패해 500 이 났다(14:40:52 첫 발생).
6. 리포트 요청은 응답이 늘어져 nginx 504, upstream 이 비활성화되며 502 가 겹쳤다(nginx 증폭은 설정 파일이 없어 미확인).
7. 14:51 에 리포트 호출이 줄어 점유가 풀리며 14:51:49 에 회복됐다.

### 4.3 배경 원인 (직접 원인을 가능하게 한 것)

| 배경 | 내용 |
|---|---|
| 풀이 작고 대기가 짧음 | `maximum-pool-size 5` · `connection-timeout 3s`. 연결을 오래 쥐는 요청 몇 개로 전체가 막힌다. |
| 연결을 쥐는 시간 제한이 없음 | 리포트 엔드포인트에 호출 제한 · 캐시 · 동시 실행 상한이 없어 한 기능의 부하가 모든 API 로 번진다. |
| 알려진 문제의 방치 | 코드에 같은 문제를 적은 `TODO` 가 이미 있었다(`ReportService.java:69-70`). |
| 경고가 행동으로 이어지지 않음 | leak 경고는 14:37:52 부터 나왔으나 장애 인지(5xx)는 3분 뒤였다. 경보 규칙이 없었던 것으로 보인다(로그로는 확인 불가). |

방아쇠(호출 증가)는 원인이 아니다. 평소 호출량에서는 같은 코드가 하루 종일 문제없었다.

## 5. 근거 로그 줄

경로: `incident-logs/a-connection-pool/` (app · acc · err · slow) 와 `modern/api/` (코드). 줄번호는 이번에 다시 열어 확인했다.

| # | 원인 서술 | 근거 (파일:줄) | 발췌 |
|---|---|---|---|
| 1 | 리포트가 `@Transactional` 로 진입해 연결을 잡는다 | `ReportService.java:41` | `@Transactional` / `public ClassReport buildClassReport(Integer classId)` |
| 2 | DB 작업 뒤 같은 트랜잭션 안에서 서명 루프를 돈다 | `ReportService.java:69-71`, `:23`, `:88` | `// DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` / `SIGNATURE_ROUNDS = 200_000` |
| 3 | 연결이 10초를 넘겨 점유됐고 그 스택이 리포트다 | `app.log:1243`, `:1253` | `Connection leak detection triggered for …Connection@6e8f14d3 on thread …exec-6` / `ReportService$$SpringCGLIB$$0.buildClassReport` |
| 4 | 점유된 연결은 리포트 완료 후에야 반납된다 | `app.log:1258-1259` | `14:38:01.900 built class report for class 3…` / `14:38:01.902 Previously reported leaked connection … was returned to the pool` |
| 5 | leak 134건 전부가 리포트다 | 3.1 의 집계(`grep -c` 134, 분류 report 134 / other 0) | (집계: 줄별 발췌 없음) |
| 6 | 풀 크기 5 · 대기 3초 | `application.yml:14-18`, `app.log:1522` | `maximum-pool-size: 5` / `request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 7 | 리포트가 아닌 요청이 풀 고갈로 실패했다 | `app.log:1523`, `nginx-access.log:18918` | `unhandled exception on /api/units/M6-2/items` / `GET /api/units/M6-2/items … 500 162` |
| 8 | 리포트 호출이 늘었다(방아쇠) | 3절 가설 3 실행 결과 | 분당 report 호출 14:38 3 · 14:40 10 · 14:41 16 · 14:46 19 |
| 9 | 리포트 요청은 504 가 되고 nginx 가 upstream 을 끊었다 | `nginx-access.log:18933`, `nginx-error.log:6`, `:10`, `:12` | `GET /api/classes/3/report … 504` / `upstream timed out` / `upstream server temporarily disabled` / `no live upstreams` |
| 10 | 마지막 누수 연결이 반납되며 회복됐다 | `app.log:5266-5267`, `nginx-access.log:19381` | `14:51:49.146 built class report…` / `Previously reported leaked connection … returned` / 마지막 504 14:51:47 |

참고: 2.1절 95행은 14:38:01 의 리포트 완료 · 반납을 `app:1279~1280` 으로 적었으나 실제 줄은 `1258-1259` 다. 1~3절은 유지하라는 요청에 따라 고치지 않았으니, 필요하면 별도로 정정한다.

## 6. 수정안 (지금 적용)

코드는 이번에 고치지 않았다. 아래는 제안이다. 대상은 `ReportService.java` 하나다.

1. **서명을 트랜잭션 밖으로 뺀다** (`ReportService.java:41`, `:71`). 조회·집계까지만 짧은 트랜잭션으로 하고 `sign()` 은 연결을 반납한 뒤 호출한다.
   - 같은 클래스 안의 호출은 프록시를 거치지 않아 `@Transactional` 이 무시되므로, 조회·집계를 별도 빈(예: `ReportQueryService`)이나 `TransactionTemplate` 로 분리한다. `buildClassReport` 자체의 `@Transactional` 은 제거한다.
2. 조회 전용 구간은 `@Transactional(readOnly = true)` 로 한다(`ItemService` · `DistributionService` 와 같은 방식).
3. `SIGNATURE_ROUNDS` 는 외부 집계 시스템의 요구라 바꾸지 않는다(`ReportService.java:22-23` 주석).
4. 풀 설정(`application.yml`)은 CLAUDE.md 금지 사항이라 건드리지 않는다. 풀 키우기는 운영 협의 항목으로만 남긴다.
5. 테스트: `ReportServiceTest` 에 서명 구간에서 트랜잭션이 활성이 아님을 확인하는 케이스를 추가한다(`TransactionSynchronizationManager.isActualTransactionActive()`). 변경 후 `./gradlew test` 와 `characterization/` `npm test` 통과를 확인한다.

## 7. 재발 방지 (구조적 장치)

수정안 적용 여부와 무관하게 같은 유형(트랜잭션 안에서 느린 작업)을 막는 장치다.

1. **규칙 자동 검사**: ArchUnit 이나 테스트로 `@Transactional` 메서드 안의 반복 루프 · 외부 호출을 잡는다(CLAUDE.md 3절 금지 사항을 기계 검사로). 의존성 추가는 먼저 묻는다.
2. **벌크헤드**: 리포트처럼 무거운 엔드포인트는 동시 실행 상한(예: 풀의 절반 미만)을 둬 다른 API 의 연결을 보호한다. 상한 초과 요청은 빨리 429/503 으로 돌려준다.
3. **캐시 · 호출 제한**: 학급 리포트 결과를 짧게 캐시하거나 클라이언트별 호출을 제한해 화면 자동 갱신이 부하로 번지지 않게 한다.
4. **TODO 추적**: `TODO` 로 남은 알려진 결함은 이슈로 등록하고 기한을 둔다. 이번 건은 코드 주석에만 있었다.
5. **부하 시험**: 풀 크기를 운영과 같게 둔 채 리포트 동시 호출 시험을 CI 나 릴리스 전 점검에 넣는다.
6. **배포 이력 확보**: 장애 시점의 버전 · 설정을 확인할 수 있게 배포 이력과 기동 로그를 남긴다(9절).

## 8. 모니터링 항목

제안 임계값과, 이번 타임라인(분 단위 집계)에서 울렸을 시각이다. 앱 지표(풀 상태)는 로그에 없어 로그에서 가장 가까운 사건으로 추정했다.

| 지표 | 임계값 (제안) | 이번에 울렸을 시각 | 근거 |
|---|---|---|---|
| Hikari leak 경고 건수 | 5분 내 1건 이상 → 주의 / 분당 3건 이상 → 경보 | 14:37:52 주의, 14:39 경보 | 첫 경고 `app.log:1243`, 14:39 에 3건/분 |
| 풀 사용률 (`active/max`) | 80% 이상 1분 지속 | 14:40 전후(추정) | 14:40:52 `active=5` 의 직전. 풀 지표 로그 없음 |
| 풀 대기 연결 수 (`waiting`) | 1 이상 30초 지속 | 14:40:52 | `app.log:1522` `waiting=4` |
| 연결 획득 타임아웃 | 1건 → 경보 | 14:40:52 | `app.log:1522` |
| 5xx 비율 | 1분 평균 1% 이상 | 14:40 분 집계(약 14:41) | 14:40 에 5xx 1건(분당 요청 약 40건 → 약 2.5%), 14:41 에 9건 |
| 엔드포인트별 p95 응답시간 (리포트) | 5초 초과 | 14:37:52 부근(추정) | 연결 반납이 9.7초 뒤. 접근 로그에 응답시간이 없어 실측 불가 |
| nginx upstream timeout / no live upstreams | 분당 1건 이상 | 14:41:06 / 14:42:04 | `nginx-error.log:6`, `:12` |
| 엔드포인트별 호출량 (리포트) | 평소(10분당 6건)의 5배 | 14:40 분 집계 | 14:40 에 분당 10건(평소 환산 분당 0.6) |

가장 이른 신호는 leak 경고(14:37:52)이고, 사용자 영향이 시작된 14:40:52 보다 약 3분 빠르다. 그 3분 안에 경고를 보고 조치했다면 영향을 줄일 여지가 있었다.

## 9. 확인하지 못한 것

| 항목 | 이유 | 다음을 위해 남길 로그 |
|---|---|---|
| `sign()` 소요 시간과 점유 중 DB 시간의 비율 | 점유 16~20초는 경고 시각에서 추정했고 실측하지 않았다(벤치마크 금지) | 메서드 구간 타이밍 로그(`dbMs` · `signMs`), 반납 로그에 점유 시간 포함 |
| 장애 당시 배포 버전 · 설정 | git 이력이 9/22 초기 커밋뿐, 기동 로그 없음 | 기동 시 버전 · 풀 설정 로그, CI/CD 배포 이력 |
| 호출 증가 원인 (14:38~) | 사용자 행동인지 화면 자동 갱신인지 로그에 없음 | 접근 로그의 Referer · 요청 시작 시각 · 응답시간, 프론트 갱신 주기 |
| 14:37 `submission` 조회 1,284,310행 · 9.8초 | DB 접속 불가로 `EXPLAIN` 을 못 했고, 장애 중에는 슬로우 쿼리가 0건 | 운영 `EXPLAIN` · `SHOW CREATE TABLE submission` · `long_query_time` 값, performance_schema 쿼리 이력 |
| nginx 증폭 (`max_fails` · `proxy_next_upstream`) | nginx 설정 파일이 저장소에 없음 | `nginx.conf` 형상, upstream 상태 변경 로그 |
| leak 연결 134건의 점유 시간 전체 분포 | 반납 로그 형식상 자동 집계 불가(6건 표본만) | 연결 ID · 점유 ms 를 한 줄로 남기는 로그 |
| leak 스택 139 대 134 의 5건 차이 | 출처를 확인하지 않았다 | 스택 분류 결과 전체 |
| 풀 지표(active · waiting) 시계열 | 로그에 없음. 8절의 사용률 시각은 추정 | Micrometer `hikaricp.connections.*` 지표 수집 |
| 이 지표들이 실제로 경보를 울렸을지 | 경보 규칙이 당시 있었는지 모름 | 알림 이력 |
