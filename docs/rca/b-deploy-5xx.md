# b-deploy-5xx 장애 원인 분석 (RCA) — 1~3절 작업본

> **작성 범위**: "1. 수집 범위", "2. 타임라인", "3. 가설과 검증" 중 **가설 표까지**만 작성했다. 요약 · 원인 · 코드 대조 · 검증 판정 · 수정안 · 재발 방지 · 모니터링 · 확인하지 못한 것(4절 이후)은 아직 쓰지 않았다. 아래 가설은 모두 **미검증**이다.

## 1. 수집 범위

대상: `incident-logs/b-deploy-5xx/` (모두 2026-09-17 기록). 이 절은 범위만 정한다. 원인 추측은 하지 않는다.
방법: `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 분 단위 집계만 사용했고 파일을 통째로 읽지 않았다.

### 1.1 파일별 기록 기간 · 줄 수 · 시각 형식

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 | 타임존 |
|---|---|---|---|---|
| `app.log` | 2,938 (타임스탬프 시작 줄 1,275 = INFO 1,194 · WARN 35 · ERROR 46, 나머지는 스택트레이스 이어지는 줄) | 09:00:04 ~ 14:39:57 | `2026-09-17T13:46:38.113+09:00` (ISO-8601) | KST (`+09:00` 명시) |
| `nginx-access.log` | 20,365 | 09:00:00 ~ 14:39:58 | `[17/Sep/2026:13:46:38 +0900]` | KST (`+0900` 명시, 전부) |
| `deploy-history.md` | 39 (배포 표 + 버전별 메모) | 2026-09-01 ~ 2026-09-17 | `2026-09-17 13:40:05` | KST (문서 머리말에 "시각은 모두 KST" 명시) |

- `nginx-error.log` · DB 슬로우 로그는 이 묶음에 없다.
- `nginx-access.log` 에는 응답 시간 · upstream 주소 필드가 없다. `app.log` 에는 호스트 식별자가 없다(어느 API 서버가 처리했는지 로그만으로 알 수 없다).

### 1.2 KST 변환 규칙

- 세 파일 모두 KST 이므로 **변환 없음**.
- 확인: ① `app.log` ERROR 46건과 `nginx-access.log` 의 500 46건을 초 단위로 대조(`diff`)하면 45건이 일치하고 1건만 1초 어긋난다(`app.log:2633` 14:28:05.996 ↔ access 14:28:06. access 는 응답 종료 시각이라 올림으로 보이며 같은 요청). ② 배포 이력의 v1.4.2 배포 구간(13:40:05 ~ 13:42:31) 안에 `app.log` 의 종료 · 기동 기록(13:41:48 ~ 13:42:00)과 access 의 502(13:41:51 ~ 13:41:59)가 들어간다.

### 1.3 분 단위 이상 건수와 이상 구간

집계 기준: `nginx-access.log` 5xx, `app.log` WARN · ERROR.

| 구분 | 건수 | 시간 분포 |
|---|---|---|
| access 5xx 합계 | 54 = 500 **46** + 502 **8** | 아래 참고 |
| 502 8건 | 13:41:51 ~ 13:41:59 | 앱 재기동 구간. 이후 13:42:01 부터 200 |
| 500 46건 | 09:52 ~ 11:18 에 **7건**(배포 41번), 13:46 ~ 14:34 에 **39건**(배포 42 · 43번 재배포) | 14:35 ~ 14:39 에는 0건 |
| `app.log` ERROR 46건 | 500 과 동일 시각 | 전부 `GlobalExceptionHandler` 의 `unhandled exception on /api/distributions/…` |
| `app.log` WARN 35건 | 09:06 ~ 14:35 에 시간당 약 6건 | 전부 409 `state conflict`(마감된 과제 재배포). 평소 수준이며 access 409 35건과 같다 |
| 트래픽 | 시간당 3,215 ~ 3,762건, 14시대 10분 단위 581 ~ 665건 | 배포 전후 증가 없음 |

- 500 46건은 **전부 클라이언트 `교사단말A` 1대**(교사 화면 `https://lms.example.com/teacher/distributions/…`)의 요청이다. 502 8건은 서로 다른 학생 · 교사 단말과 헬스체크(`kube-probe`) 요청이다.
- 500 의 경로는 `POST …/41/redistribute` 6 · `GET …/41` 1 · `POST …/42/redistribute` 19 · `POST …/43/redistribute` 20 이다.
- 평소 수준: 5xx 는 **0건이 아니다**. 배포 전(09:00 ~ 13:40)에도 배포 41번에 대한 500 이 7건 있었다. 그 밖의 경로 5xx 는 배포 전 0건이다.

**이상 구간 제안**
- 구간 A (재기동 502): 13:41:51 ~ 13:41:59.
- 구간 B (재배포 500, 배포 후): 13:46:38 ~ 14:34:45.
- 사전 신호 C (배포 41번 500, 배포 전): 09:52:17 ~ 11:18:02.

### 1.4 앞뒤 여유 제안

- **수집 범위: 12:40 ~ 14:39 KST** (로그 끝까지). 배포 시작(13:40:05) 60분 전부터, 이상 구간 B 가 끝난 뒤 복구 확인용으로 14:35 ~ 14:39 를 포함한다.
- 예외: 사전 신호 C(09:52 ~ 11:18)는 범위 밖이지만 배포 전에도 같은 종류의 오류가 있었다는 증거라서 별도로 포함한다.
- 그 밖의 09:00 ~ 12:40 은 `grep -c` · 분 단위 건수로만 확인했다.

### 1.5 한계

- 응답 시간 · upstream 주소가 없어 지연과 호스트별 분포는 이 로그로 볼 수 없다.
- 14:39:58 이후 기록이 없어 14:35 이후 재발이 없는지는 약 5분 치만 확인했다. 마지막 500 은 14:34:45 이고 이후 같은 교사단말A 의 재배포 요청이 없었는지(요청이 없어서 오류도 없는 것인지)는 아직 가려내지 않았다.
- `deploy-history.md` 는 사람이 쓴 문서이므로 로그 시각과 대조해 사용한다.

## 2. 타임라인

대상: 12:40 ~ 14:39 KST 및 사전 신호 C. 모든 시각은 KST. 로그에 있는 사실만 적었고 원인 해석은 하지 않는다.

- 출처 표기: `app`=`app.log`, `acc`=`nginx-access.log`, `dep`=`deploy-history.md`.
- `acc` 는 초 단위, `app` 은 밀리초 단위다. 같은 초 안에서 선후를 알 수 없으면 (선후 불확실)로 표시했다.
- 발췌에서 IP 는 역할 별칭으로 바꿨다: `교사단말A` = 500 을 낸 교사 단말, `API-1` = 배포 대상 호스트, `API-2` = 평소 정지된 대기 호스트. 학생 식별자는 `STU-…` 로 줄였다.

### 2.1 사건 타임라인

| 시각 | 출처 (파일:줄) | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 09:52:17 | acc:3150 / app:194 | **사전 신호 C 시작**: 교사단말A 의 `POST /api/distributions/41/redistribute` 500. app 에는 `Assignment` 식별자 9 가 없다는 예외. 같은 요청이 09:52:30 (acc:3162), 09:52:41 (acc:3174) 에 반복 | `unhandled exception on /api/distributions/41/redistribute` / `Entity \`com.example.assignment.Assignment\` with identifier value \`9\` does not exist` (선후 불확실: acc · app 같은 초) |
| 09:52:42 | app:308 | 같은 분에 배포 44번 재배포는 정상 처리(배포 41번만 실패) | `redistributed distribution 44 (assignment 5, class 2) reason=null` |
| 10:31:05 | acc:5563 / app:451 | 배포 41번 **단건 조회**(`GET /api/distributions/41`)도 500. 스택은 `getDistribution` → `loadOrThrow` | `GET /api/distributions/41 HTTP/1.1" 500 176` |
| 11:17:40 ~ 11:18:02 | acc:8442, 8466, 8468 / app:655, 695, 733 | 배포 41번 재배포 500 3건. 이후 13:41 까지 배포 41번 요청은 로그에 없음 | `POST /api/distributions/41/redistribute HTTP/1.1" 500 176` |
| 13:40:05 | dep (표 v1.4.2 행) | v1.4.2 **배포 시작**. 변경: 재배포 응답에 학급 배포 이력(history) 포함, 변경 파일은 `DistributionController.java` 1개. 배포 방식 "단일 호스트 재기동(무중단 아님), 예상 중단 10초 안팎", 대상 호스트 `API-1` | `v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 …` |
| 13:41:20 | app:1238 | 종료 직전 마지막 재배포 정상 처리 (배포 43번, class 1) | `redistributed distribution 43 (assignment 6, class 1) reason=STU-… 요청 — 제출 화면 오류` |
| 13:41:48 → 13:41:50 | app:1240 ~ 1244 | 앱 종료: graceful shutdown 시작 → 완료 → DB 풀 종료 | `Commencing graceful shutdown. Waiting for active requests to complete` / `itembank-pool - Shutdown completed.` |
| 13:41:50 | acc:16862 ~ 16864 | 종료 완료 시각까지 200 응답 | `GET / HTTP/1.1" 200` |
| 13:41:51 ~ 13:41:59 | acc:16865 ~ 16872 | **502 8건** (학생 단말 · 헬스체크 포함, 서로 다른 경로) | `GET /api/units/M5-2/items?student=STU-… HTTP/1.1" 502 157` / `GET / HTTP/1.1" 502 157 "-" "kube-probe/1.29"` |
| 13:41:55 | app:1245 | 새 버전 기동 시작 (v1.4.2, Java 21.0.4) | `Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1` |
| 13:42:00 | app:1264 | 기동 완료 (4.921초). 직후 13:42:01 부터 access 200 (acc:16873) | `Started ItemBankApplication in 4.921 seconds` |
| 13:42:10 | acc:16884 | 기동 후 첫 재배포 요청 200 (배포 7번). 재기동 후 다른 학급 재배포는 정상 | `POST /api/distributions/7/redistribute HTTP/1.1" 200` |
| 13:46:38 | acc:17155 / app:1274 ~ 1275 | **구간 B 시작**: 교사단말A 의 배포 42번 재배포. app 에 재배포 INFO 가 먼저 찍히고 6ms 뒤 같은 요청에서 ERROR. 이번 스택에는 `listByClass` · `DistributionController.redistribute(…:40)` 프레임이 있다 | `redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` → `unhandled exception on /api/distributions/42/redistribute` (선후: app 줄 순서로 INFO → ERROR) |
| 13:46:38 | app:1286 | 스택의 첫 `listByClass` 프레임. 예외는 동일하게 `Assignment` 식별자 9 없음 | `at com.example.assignment.DistributionService.listByClass(DistributionService.java:38)` |
| 13:46:58, 13:47:08 | acc:17170, 17181 | 같은 요청(배포 42번) 500 반복 | `POST /api/distributions/42/redistribute HTTP/1.1" 500 176` |
| 13:50:12 ~ 14:34:45 | acc:17365 … 20084 | 구간 B 계속: 배포 42번 19건 · 43번 20건, 모두 500 · 응답 크기 176 · 교사단말A. 건당 INFO(재배포 처리) → ERROR 순서. 배포 후 구간에서 42 · 43번의 재배포 200 은 **0건** (배포 전 42번 200 5건 · 43번 200 12건) | `POST /api/distributions/43/redistribute HTTP/1.1" 500 176` |
| 13:42 ~ 14:39 | acc / app | 같은 구간 다른 배포의 재배포는 정상: 44번 200 1건, 5 · 7 · 8번 200 5건. 409(마감 과제)는 평소처럼 발생 | `redistributed distribution 44 (assignment 5, class 2) …` |
| 14:34:45 | acc:20084 / app:2887 | **마지막 500**. 이후 14:39:58 까지 5xx 0건 | `unhandled exception on /api/distributions/43/redistribute` |
| 14:39:57 | app 마지막 줄 | 로그 종료(404 `no handler for /favicon.ico` INFO) | `no handler for /favicon.ico` |

### 2.2 타임라인에서 눈에 띄는 사실 (해석 아님)

- 502 는 앱이 내려가 있던 약 10초(13:41:50 ~ 13:42:00)와 겹친다. 배포 이력의 예상 중단(10초 안팎)과 같은 크기다.
- 500 은 배포 전후 모두 **같은 예외 문구**(`Assignment` 식별자 `9` 없음)이고 같은 단말이 낸다. 배포 전에는 배포 41번(`loadOrThrow`), 배포 후에는 배포 42 · 43번(`listByClass`)이다.
- 구간 B 의 500 은 재배포 INFO 가 찍힌 요청에서 난다(app 줄 순서 기준).
- 배포 후 42 · 43번 재배포 성공은 0건이다. 배포 전에는 17건 중 17건이 성공했다.

## 3. 가설과 검증

이 절은 **가설 표까지만** 작성했다. 검증 판정 · 코드 · 설정 대조는 하지 않았다. 증상(502 · 500)과 원인 후보를 구분했다.

- 증상 S1: 13:41:51 ~ 13:41:59 에 502 8건.
- 증상 S2: 13:46:38 ~ 14:34:45 에 교사단말A 의 배포 42 · 43번 재배포가 500 (39건, 성공 0건).
- 증상 S3: 09:52 ~ 11:18 에 배포 41번이 500 (7건, 배포 전).

### 3.1 가설 표

| ID | 설명하려는 증상 | 계층 | 가설 | 지지 근거 (로그) | 반증 조건 | 확인 방법 (명령 · 조회) |
|---|---|---|---|---|---|---|
| H1 | S1 | 배포 설정 | v1.4.2 배포가 **단일 호스트 재기동(무중단 아님)** 이라 앱이 내려가 있던 약 10초 동안 nginx 가 502 를 냈다. 대기 호스트 `API-2` 로 넘기는 절차가 없었다 | `dep`: "단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎". `app:1240 ~ 1264`: 종료 13:41:48 ~ 기동 13:42:00. `acc:16865 ~ 16872`: 502 8건이 13:41:51 ~ 13:41:59 에만 있고 13:42:01 부터 200 | 502 가 재기동 구간 밖에서도 나오거나, 재기동 구간에 502 가 아니라 정상 응답이 섞여 나온다. 또는 재기동 중에도 대기 호스트가 응답했다는 증거가 있다 | `awk '$9==502' nginx-access.log \| wc -l` 과 시각 범위 확인. nginx upstream 설정(`proxy_next_upstream` · backup 서버)과 `API-2` 정지 상태 확인 |
| H2 | S2 | 코드 (배포 변경분) | v1.4.2 에서 추가된 `DistributionController.redistribute` 의 `listByClass(classId)` 호출이 학급 전체 배포를 조회하는데, 그 안에 `Assignment` 9 를 잃어버린 배포가 있어 예외가 난다. **재배포는 이미 처리된 뒤** 응답 단계에서 500 이 된다 | `app:1274 ~ 1275`: INFO(재배포 처리) 6ms 뒤 ERROR. `app:1286`, `app:1313 …`: 스택에 `listByClass(DistributionService.java:38)` · `DistributionController.redistribute(…:40)` — 배포 전 500 스택에는 없던 프레임. 배포 후 42 · 43번 성공 0건 / 배포 전 17건 성공. 같은 시각 다른 학급(배포 44 · 5 · 7 · 8번)은 성공 | 배포 후 42 · 43번 요청 중 `listByClass` 프레임이 없는 500 이 있다. 또는 `listByClass` 호출이 있어도 성공하는 42 · 43번 요청이 배포 후에 있다. 또는 배포 전 코드에서도 `listByClass` 가 호출된다 | `grep -c 'listByClass(DistributionService.java:38)' app.log` 와 ERROR 46건 중 프레임 수 대조(이미 39 vs 7 확인). `git show v1.4.2 -- DistributionController.java` 로 호출 추가 확인(코드 대조는 이번에 하지 않음) |
| H3 | S2 · S3 | DB 데이터 | `Assignment` 식별자 9 를 가리키는 **고아 배포 행**(`distribution.assignment_id = 9` 인데 `assignment` 에 9 가 없음)이 있다. 배포 41번이 단건 조회 · 재배포 시 같은 예외를 내고(S3), 같은 학급의 배포를 모두 불러오는 새 경로가 이 행에 닿으면 S2 가 된다 | 예외 문구가 46건 전부 `Assignment … identifier value \`9\` does not exist` (`grep -E 'Assignment\`' app.log` 92줄 = 46건 × 예외 · 원인 2줄). 배포 41번은 배포 전부터 단건 조회(`acc:5563`) · 재배포(`acc:3150` …)가 실패. 정상 배포의 assignment 는 4 · 5 · 6 (`app:1274` 등) | 예외 문구의 식별자가 9 가 아닌 500 이 있다. 또는 DB 에서 `assignment` 9 가 존재한다. 또는 배포 41번이 어느 학급에도 속하지 않는다 | `SELECT d.id, d.class_id, d.assignment_id FROM distribution d LEFT JOIN assignment a ON a.id=d.assignment_id WHERE a.id IS NULL;` (스테이징/읽기 전용 사본에서만. 이번에는 실행하지 않음) |
| H4 | S2 | 트래픽 · 클라이언트 | 교사단말A 의 **반복 클릭 · 자동 재시도**가 500 건수를 부풀렸다. 오류 자체는 H2 · H3 이 원인이고 건수는 트래픽 증가가 아니라 재시도 때문이다 | `acc`: 500 46건 전부 `교사단말A`. 같은 경로가 3~10초 간격으로 2~4회 연속(`acc:17155 → 17170 → 17181`, `acc:17365 → 17366 → 17376 → 17400`). 전체 요청량은 배포 전후 같다(시간당 3,215 ~ 3,762, 14시대 10분당 581 ~ 665) | 500 이 교사단말A 외 단말에서도 나온다(access 조사에서는 46건 모두 한 단말). 또는 배포 후 요청량이 배포 전의 1.5배 이상으로 늘었다 | `awk '$9==500{print $1}' nginx-access.log \| sort \| uniq -c` (이미 확인). 단말A 의 `User-Agent` · 화면 재시도 로직 확인(프런트 코드 대조는 이번에 하지 않음) |
| H5 | S2 | 트랜잭션 · 데이터 일관성 | 재배포는 처리된 뒤(INFO) 응답 단계에서 500 이 되므로, 교사 화면이 실패로 보고 **재시도해 재배포가 중복 실행**됐을 수 있다. 같은 `reason` 으로 INFO 가 반복되는 것이 근거다 | `app:1274, 1312, 1350`: 배포 42번 INFO 가 13:46:38 · 13:46:58 · 13:47:08 에 3회, `reason` 값은 서로 다름. `app:1395 ~ 1511`: 배포 43번이 13:50 에 4회. INFO 건수(재배포 처리) 와 500 건수가 같은 시각대에서 일치 | 재배포 INFO 가 500 과 별개로 한 번만 남아 있거나, 500 건마다 INFO 가 대응하지 않는다. 또는 재배포가 롤백됐다는 로그가 있다 | `grep -nE 'redistributed distribution 4[23]' app.log` 건수와 500 건수 대조. 실제 반영 여부는 DB 의 재배포 이력 조회가 필요(이번에는 실행하지 않음) |

### 3.2 가설 간 관계 (판정 아님)

- H1 은 S1 만 설명한다. S2 · S3 과는 별개 계층(배포 설정)이다.
- H2 · H3 은 함께 있어야 S2 가 설명된다. H3 만으로는 배포 전에 42 · 43번이 정상이었던 이유를 설명하지 못하고, H2 만으로는 `Assignment` 9 가 없는 이유를 설명하지 못한다. 둘 중 하나만으로는 불충분하다는 점이 이 표의 쟁점이다.
- H4 · H5 는 영향 범위 가설이다. 오류를 만드는 가설이 아니다.
- 배포 41번 문제(S3)가 배포 전부터 있었다는 점은 "v1.4.2 가 원인인가 · 기존 데이터 문제가 드러났나"를 가르는 핵심 사실이다. 이 판단은 4절 이후(검증)에서 한다.

> **여기까지 작성하고 멈춘다.** 코드 · 설정 대조, 가설 판정, 4절 이후는 요청에 따라 작성하지 않았다.
