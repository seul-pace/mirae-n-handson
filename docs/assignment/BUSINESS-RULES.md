# legacy/assignment-thymeleaf 비즈니스 규칙 후보

> 분석 범위: `legacy/assignment-thymeleaf` 의 Java 소스 전부(모델 5개 포함), 템플릿 5개, `application.properties`, `AppClockTest.java`.
> 경로 약어: `J/` = `legacy/assignment-thymeleaf/src/main/java/com/example/assign/`,
> `T/` = `legacy/assignment-thymeleaf/src/main/resources/templates/`, `S` = `db/mariadb/init/01-schema.sql`
> 함께 읽을 문서: [ARCHITECTURE.md](ARCHITECTURE.md), [ERD.md](ERD.md)
> 이 문서는 규칙 "후보"다. 코드가 실제로 하는 일을 적었고, 그것이 의도된 정책인지는 확인하지 않았다.

## 0. 핵심 관점별 판정표

분석의 중심은 아래 네 가지다. 이 절은 2절의 규칙을 관점별로 모아 판정 순서와 조합으로 다시 정리한 것이다. 근거는 각 규칙에 있다.

1. 교사가 학급에 과제를 배포할 때의 조건 → 0.1
2. 마감 판정 → 0.2
3. 재배포가 허용되는 경우와 막히는 경우 → 0.3
4. 제출 상태 전환 → 0.4

첫 요청의 "문항 검색(단원 · 난이도 · 태그)", "등록 · 수정 검증"은 이 모듈에 해당 코드가 없어 관점에서 뺐다. 문항 검색 코드는 없고, 단원은 과제 목록에 표시만 한다(`J/dao/AssignmentDao.java:22,24`). 목록의 정렬 · 제외 조건은 2.1절에 그대로 둔다.

### 0.1 배포 조건 (`POST /distributions`)

**검사 순서.** 앞에서 걸리면 뒤 검사는 하지 않는다 → BR-63

| 순서 | 위치 | 조건 | 결과 | 규칙 |
|---|---|---|---|---|
| 1 | 컨트롤러 | 과제가 없다 | 거부 "존재하지 않는 과제입니다." | BR-11 |
| 2 | 컨트롤러 | 마감 시각 < 현재 시각 이고 상태 ≠ `'X'` | 거부 "마감(…)이 지난 과제는 배포할 수 없습니다." | BR-12 |
| 3 | 서비스 | 과제가 없다 | 거부. 1에서 이미 걸러지므로 사실상 도달하지 않는다 | BR-11 |
| 4 | 서비스 | 학급이 없다 | 거부 | BR-11 |
| 5 | 서비스 | 상태 = `'D'` | 거부 "삭제된 과제는 배포할 수 없습니다." | BR-11 |
| 6 | 서비스 | 같은 과제 · 학급 배포가 이미 있다 | 거부 "이미 이 학급에 배포된 과제입니다…" | BR-13 |
| 7 | 서비스 | 저장 건수 ≠ 1 | 실패 | BR-15 |
| 통과 | | | id = MAX+1, `distributed_at` = 현재 시각, `redistributed` = 0 으로 저장 | BR-14, BR-15 |

**과제 상태별 결과.** 현재 시각은 BR-62 기준이다.

| 과제 상태 | 선택 목록에 나오나 (BR-03) | 목록에서 고를 수 있나 (`T/distribution_form.html:16`) | 서버: 마감 전 | 서버: 마감 후 |
|---|---|---|---|---|
| `O` 진행중 | 나온다 | 마감 전만 | 허용 | 거부(2) |
| `X` 연장 | 나온다 | 항상 | 허용 | **허용** |
| `C` 종료 | 나온다 | 마감 전만 | **허용** | 거부(2) |
| `R` 검수중 | 안 나온다 | - | **허용**(직접 POST) | 거부(2) |
| `D` 삭제 | 안 나온다 | - | 거부(5) | 거부(2). 마감 메시지가 먼저 나온다 |
| 빈 값 · 그 밖의 코드 · 소문자 | 안 나온다 | - | 허용(직접 POST) | 거부(2). 소문자 `x` 는 연장으로 보지 않는다 |

- 학급 쪽 조건은 "학급이 있을 것" 하나다. 담당 교사와 배포하는 사람이 같은지는 보지 않는다. 배포하는 사람을 받는 파라미터가 없다(`J/web/DistributionController.java:50-52`).
- **시드 데이터가 중복 배포 금지(BR-13)와 맞지 않는다.** 배포 3 · 4 가 모두 과제 2 → 1반이다. 시드 주석은 "4번은 2번 과제를 1반에 재배포한 이력"이다(`db/mariadb/init/02-seed.sql:128,133-134`). 예전에는 재배포가 새 배포 행을 만들었고 지금 코드는 플래그만 바꾼다(BR-53). 이력 데이터와 현재 규칙이 서로 다르다.

### 0.2 마감 판정

"마감이 지났다"를 판정하는 곳이 다섯 군데이고 비교 방식이 서로 다르다.

| 사용처 | 비교 방식 | 마감 정각 | 마감 + 30초 | 마감 + 71시간 59분 | 마감 + 72시간 | 마감 NULL | `X` 예외 | 규칙 |
|---|---|---|---|---|---|---|---|---|
| 새 배포 서버 검사 | `due.isBefore(now)`, 초 단위 | 허용 | 거부 | 거부 | 거부 | 검사 안 함 | 있음 | BR-12 |
| 새 배포 선택 목록 `disabled` | 위와 같음 | 선택 가능 | 선택 불가 | 선택 불가 | 선택 불가 | 선택 가능 | 있음 | BR-12 |
| 과제 목록 흐림 표시 | 위와 같음 | 안 흐림 | 흐림 | 흐림 | 흐림 | 안 흐림 | 있음 | BR-09 |
| 재배포 허용 | 분 단위 차이 → 0분까지 "마감 전 · 정각", 그 뒤는 시간 단위(버림)로 72 미만이면 유예 | 허용 | 허용(0분 = 정각) | 허용(유예) | 거부 | 마감 규칙 없이 허용 | 있음(경과 무관 허용) | BR-32 ~ BR-36 |
| 지연 제출 | `submittedAt.isAfter(due)`, 초 단위 | 지연 아님 | 지연 | 지연 | 지연 | 판정 안 함 | 없음 | BR-41 |

- **마감이 지나면 새 배포는 곧바로 막히지만 재배포는 72시간까지 된다.** 두 기준이 다르다.
- 재배포 유예 72시간은 주석의 "7일"(`J/service/DistributionService.java:318`)과 다르다(BR-36).
- 재배포 판정의 구간 문구(마감 전 / 임박 / 당일 / 유예 / 유예 종료 / n일 / n개월, BR-35)는 허용 여부에 영향이 없다. 허용 여부는 "정각 이전", "72시간 미만", "`X`" 세 가지로만 정해진다.
- 모든 "현재 시각"은 기본이 `2026-09-15 00:00:00` 고정이다(BR-62).

### 0.3 재배포 허용 · 차단

**판정 순서** (`J/service/DistributionService.java:72-640`) → BR-64

| 순서 | 조건 | 결과 | 코드 | 규칙 |
|---|---|---|---|---|
| 화면 | 과제 상태 `D`, 또는 과제 · 학급 행이 없는 배포 | 배포 목록에 안 나와 버튼이 없다 | - | BR-04, BR-05 |
| 화면 | `redistributed = 1` 이고 과제 상태 ≠ `'X'` | 버튼이 없다 | - | BR-39, BR-65 |
| 0 | 사유 · 작업자 입력값 | 정리만 하고 막지 않는다 | - | BR-16 ~ BR-23 |
| 1 | 배포가 없다 | 거부 | E101 | BR-24 |
| 2 | 과제가 없다 | 거부. 도달하지 않는다 | E102 | BR-05 |
| 3 | 상태 `D` | 거부 | E103 | BR-25 |
| 4 | 상태 `R` | 거부 | E104 | BR-26 |
| 5 | 마감 시각 NULL | 통과 | - | BR-32 |
| 6 | 현재 ≤ 마감(분 단위) | 통과 | - | BR-33 |
| 7 | 상태 `X` | 통과 | - | BR-34 |
| 8 | 마감 후 72시간 미만 (상태 `C` 포함) | 통과 | - | BR-36, BR-28 |
| 9 | 마감 후 72시간 이상 | 거부 | 이력 없으면 E105, 있으면 E106 | BR-36, BR-38 |
| 10 | 5 ~ 8 을 통과했고, 이력 있음(`redistributed = 1`), 상태 ≠ `'X'`, 사유 분류 ≠ 1(오류 · 장애) | 거부 | E106 | BR-39 |
| 11 | 플래그 갱신 0건 | 실패 | E107 | BR-54 |
| 12 | DB 예외 | 실패 "DB 오류로 재배포에 실패했습니다." | - | `J/web/DistributionController.java:87-89` |
| 통과 | | `redistributed = 1` 로 갱신 후 성공 | OK / OK-W / OK-W*n* | BR-53, BR-60 |

**조합표.** "72h 미만"은 마감 전 · 마감 NULL 을 포함한다.

| 과제 상태 | 이력 없음 · 72h 미만 | 이력 없음 · 72h 이상 | 이력 있음 · 72h 미만 | 이력 있음 · 72h 이상 |
|---|---|---|---|---|
| `O` · `C` · 빈 값 · 그 밖의 코드 | 허용 | 거부 E105 | 사유가 오류 · 장애면 허용, 아니면 E106. **화면에는 버튼이 없다** | 거부 E106 (메시지는 유예 경과) |
| `X` | 허용 | 허용 | 허용 | 허용 |
| `R` | E104 | E104 | E104 | E104 |
| `D` | E103 (화면에 안 보임) | E103 | E103 | E103 |

- 재배포하는 사람 조건은 없다. 담당 교사가 아니어도 메모만 남는다(BR-31). 화면은 작업자를 `teacher-01` 로 고정해 보낸다.
- 재배포 버튼은 마감 경과를 보지 않는다. 유예가 지난 배포에도 버튼이 보이고, 누르면 E105 가 난다(BR-65).
- 재배포 횟수 상한은 없다. `X` 상태면 몇 번이든 된다.

**시드 데이터 대입** (현재 시각 `2026-09-15 00:00`, 사유 "기타" 가정. 코드를 따라가 본 결과이고 실행하지 않았다)

| 배포 | 과제(상태, 마감) | 이력 | 결과 |
|---|---|---|---|
| 1, 2 | 1 (`C`, 08-20 23:59) | 없음 | E105 |
| 3 | 2 (`C`, 08-28 23:59) | 없음 | E105 |
| 4 | 2 (`C`, 08-28 23:59) | 있음 | E106 |
| 5 | 3 (`O`, 09-10 23:59) | 없음 | E105 (마감 후 96시간) |
| 6 | 4 (`O`, 09-25 23:59) | 없음 | 허용. 재제출 대상 0명, 유지 3건 |
| 7 | 5 (`O`, 10-02 23:59) | 없음 | 허용. 재제출 대상 0명, 유지 3건(채점전 2건 포함) |
| 8 | 6 (`O`, 09-30 23:59) | 없음 | 허용. 재제출 대상 0명, 유지 6건. 제출 6건 모두 현재보다 미래라 경고, 같은 학생 중복 2건 경고 |

### 0.4 제출 상태 전환

**가장 중요한 점: 재배포는 제출 데이터를 하나도 바꾸지 않는다.** DB 쓰기는 `distribution.redistributed = 1` 한 줄뿐이다(BR-53). 아래의 "전환"은 결과 메시지에 쓰는 **계산**이다. 학생에게 제출을 다시 열어 주는 코드는 없다.

**제출 행의 상태** (BR-40, BR-41, BR-46)

| `submitted_at` | `score` | 상태 | 붙는 표시 |
|---|---|---|---|
| NULL | NULL | 미제출 | - |
| NULL | 있음 | 채점완료(제출시각없음) + 경고 | 등급 |
| 있음 | NULL | 채점전 | 마감 후면 `/지연` |
| 있음 | 있음 | 채점완료 | 마감 후면 `/지연`, 등급 |

**재배포 시 전환** (BR-55 ~ BR-58, BR-66)

| 상태 | 기본 | 과제 상태 `X` | 사유 분류 4(재시험 · 재평가) | 유지 건수에 들어가나 |
|---|---|---|---|---|
| 미제출 | 재제출 대상 | 재제출 대상 | 재제출 대상 | 아니오 |
| 채점전 | 유지 | **재제출 대상** | **재제출 대상** (전환 문구는 "유지", BR-66) | 예 |
| 채점완료 · 채점완료(제출시각없음) | 유지 | 유지 | **재제출 대상, 점수 유지** | 예 |

- `X` 이면서 사유 분류 4 이면 사유 분류 4 가 이긴다. 전원이 재제출 대상이다.
- 지연 제출(`/지연`)은 전환에 영향이 없다.
- 행 단위로 센다. 같은 학생의 중복 행은 두 번 센다(BR-44).
- **정상 데이터에서는 "미제출" 행이 생기지 않는다.** 스키마가 `submitted_at` 을 NOT NULL 로 둔다(`S:109`). 시드 데이터에도 미제출 행이 없다(`db/mariadb/init/02-seed.sql:143-181`). 제출하지 않은 학생은 행이 아예 없고, 학급 명단 테이블도 없다. 그래서 사유가 재평가가 아니고 상태가 `X` 도 아니면 **재제출 대상은 늘 0명**이다. 주석의 "미제출자만 재제출 대상"(`J/service/DistributionService.java:484`)이 실제 데이터에서는 아무도 가리키지 않는다.
- 화면에 나오는 것은 "재제출 대상 n명, 기존 제출 n건 유지" 한 줄뿐이다(`:648-651`). 전환 요약 문구와 미제출 학생 목록은 만들지만 화면에 쓰지 않는다(BR-37, BR-66).

## 1. 요약

- 규칙 후보 **67개** (BR-01 ~ BR-67). 확신도 "확실" 65개, "추정" 2개.
- 관점별 판정표는 0절에 있다.
- 주석 · 문구와 코드가 다른 곳, 같은 규칙이 여러 곳에 다르게 구현된 곳은 5절에 따로 모았다.
- 매직 넘버는 4절 표에 모았다.

주의가 필요한 것 8개:

1. **재배포 유예 기간**: 주석은 "마감 후 7일 이내"(`J/service/DistributionService.java:318`), 코드는 **72시간**(`:319`)이다 → BR-36
2. **마감 기준이 둘이다.** 새 배포는 마감을 1초만 넘겨도 막히고, 재배포는 72시간까지 된다 → 0.2
3. **마감 지난 과제의 새 배포 금지**는 컨트롤러에만 있고 서비스 `distribute()` 에는 없다 → BR-12
4. **검수중(R) 과제**: 선택 목록에서는 빠지지만 서버의 새 배포 검증은 삭제(D)만 막는다 → BR-03, BR-11
5. **두 번째 재배포**: 서비스는 "연장 상태 또는 사유가 시스템 오류"면 허용하지만, 화면 버튼은 "연장 상태"일 때만 보인다 → BR-39
6. **재배포는 제출 상태를 실제로 바꾸지 않는다.** 재제출 대상은 계산해 메시지에 적을 뿐이고, 정상 데이터에서는 사유가 재평가이거나 상태가 연장이 아니면 늘 0명이다 → 0.4
7. **시드 데이터에 같은 과제 · 학급의 배포가 두 건 있다.** 지금 코드의 중복 배포 금지와 맞지 않는다 → 0.1, BR-13
8. **"제출 수"의 정의가 두 가지**다. 목록 화면은 `submitted_at IS NOT NULL`, 재배포 계산은 "미제출이 아닌 행"(점수만 있는 행 포함) → BR-06, BR-58

## 2. 규칙

### 2.1 목록 · 정렬 · 제외 조건

**BR-01**
- 규칙: 과제 상태가 `'D'` 이면 과제 목록에서 제외한다.
- 근거: `J/dao/AssignmentDao.java:26,29`
- 근거 코드:
  ```java
  // 목록: 삭제('D') 제외, 마감 빠른 순
  + " WHERE a.status <> 'D' "
  ```
- 확신도: 확실
- 비고: 스키마 주석에는 `O`, `C` 만 있고 `D` 가 없다(`S:87`). `D` 를 "삭제"로 읽는 근거는 이 주석과 서비스 메시지(`J/service/DistributionService.java:57,199`)다.

**BR-02**
- 규칙: 과제 목록과 새 배포용 과제 선택 목록은 마감 시각 오름차순, 같으면 과제 id 오름차순으로 정렬한다.
- 근거: `J/dao/AssignmentDao.java:30,37`
- 근거 코드:
  ```java
  + " ORDER BY a.due_at ASC, a.id ASC";
  ```
- 확신도: 확실
- 비고: 주석 "마감 빠른 순"(`:26`)과 일치한다.

**BR-03**
- 규칙: 새 배포 화면의 과제 선택 목록에는 상태가 `'O'`, `'X'`, `'C'` 인 과제만 나온다.
- 근거: `J/dao/AssignmentDao.java:36`, 호출 `J/web/DistributionController.java:42`
- 근거 코드:
  ```java
  + " WHERE a.status IN ('O', 'X', 'C') "
  ```
- 확신도: 확실
- 비고: 검수중(`R`), 빈 값, 알 수 없는 코드도 빠진다. 그러나 서버 쪽 새 배포 검증은 `D` 만 막는다(BR-11). `R` 과제 id 를 직접 POST 하면 배포된다. 같은 규칙이 두 곳에 다르게 구현돼 있다.

**BR-04**
- 규칙: 과제 상태가 `'D'` 인 배포는 배포 목록에서 제외하고, 목록은 과제 마감 시각 오름차순, 같으면 배포 id 오름차순으로 정렬한다.
- 근거: `J/dao/DistributionDao.java:33-34`
- 근거 코드:
  ```java
  + " WHERE a.status <> 'D' "
  + " ORDER BY a.due_at ASC, d.id ASC";
  ```
- 확신도: 확실
- 비고: 정렬 기준이 배포 시각(`distributed_at`)이 아니라 과제 마감 시각이다.

**BR-05**
- 규칙: 연결된 과제나 학급 행이 없는 배포는 배포 목록과 배포 단건 조회에서 나오지 않는다.
- 근거: `J/dao/DistributionDao.java:28-29,39`
- 근거 코드:
  ```java
  + " JOIN assignment a ON a.id = d.assignment_id "
  + " JOIN `class` c ON c.id = d.class_id ";
  ```
- 확신도: 확실
- 비고: INNER JOIN 이다. 그래서 재배포에서 "과제 없음"(E102, `J/service/DistributionService.java:164-170`)과 "학급 없음"(`:242-245`) 분기는 도달할 수 없다. `findById` 가 먼저 `null` 을 돌려 E101 로 끝난다. FK 선언이 있어(`S:101-102`) 이런 행이 생기기도 어렵다.

**BR-06**
- 규칙: 배포 목록의 "제출 수"는 그 배포의 제출 중 `submitted_at` 이 NULL 이 아닌 건수다.
- 근거: `J/dao/DistributionDao.java:26`, 표시 `T/distributions.html:28`
- 근거 코드:
  ```java
  + " (SELECT COUNT(*) FROM submission s WHERE s.distribution_id = d.id AND s.submitted_at IS NOT NULL) AS submitted_cnt "
  ```
- 확신도: 확실
- 비고: 재배포 계산의 제출 건수(BR-58)는 점수만 있고 제출 시각이 없는 행도 포함한다. 코드도 차이를 알고 있다(`J/service/DistributionService.java:636-638` 주석 "제출 건수 집계는 submitted_at 기준, 여기 계산은 score 포함 → 다를 수 있음").

**BR-07**
- 규칙: 과제 목록의 "배포 학급 수"는 그 과제의 배포에 나오는 서로 다른 학급 수다.
- 근거: `J/dao/AssignmentDao.java:23`, 표시 `T/assignments.html:27`
- 근거 코드:
  ```java
  + " (SELECT COUNT(DISTINCT d.class_id) FROM distribution d WHERE d.assignment_id = a.id) AS class_cnt "
  ```
- 확신도: 확실
- 비고: 재배포 여부와 관계없이 센다.

**BR-08**
- 규칙: 새 배포 화면의 학급 선택 목록은 제외 조건 없이 학급 id 오름차순이다.
- 근거: `J/dao/ClassDao.java:21`
- 근거 코드:
  ```java
  return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
- 확신도: 확실

**BR-09**
- 규칙: 마감 시각이 현재보다 이전이고 상태가 `'X'` 가 아닌 과제는 과제 목록에서 흐리게(`past`) 표시한다.
- 근거: `T/assignments.html:21`, 스타일 `T/layout.html:13`
- 근거 코드:
  ```html
  <tr th:each="a : ${rows}" th:classappend="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'} ? 'past'">
  ```
- 확신도: 확실

**BR-10**
- 규칙: 과제 상태 코드는 `O` 진행중, `X` 연장, `C` 종료, `R` 검수중으로 표시하고, 그 밖의 코드는 코드 값 그대로 표시한다.
- 근거: `T/assignments.html:26`, `J/service/DistributionService.java:181-212`
- 근거 코드:
  ```html
  <td th:text="${a.status == 'O'} ? '진행중' : (${a.status == 'X'} ? '연장' : (${a.status == 'C'} ? '종료' : (${a.status == 'R'} ? '검수중' : ${a.status})))"></td>
  ```
- 확신도: 확실
- 비고: 스키마 주석은 `C=마감`(`S:87`), 코드는 `C` 를 "종료"로 부른다. 서비스는 `D` "삭제", 빈 값 "미지정", 그 외 "알수없음(코드)" 라벨을 더 쓴다.

### 2.2 새 배포 등록 검증 (`POST /distributions`)

**BR-11**
- 규칙: 과제가 없거나, 학급이 없거나, 과제 상태가 `'D'` 이면 새 배포를 거부한다.
- 근거: `J/web/DistributionController.java:53-57`, `J/service/DistributionService.java:48-58`
- 근거 코드:
  ```java
  if ("D".equals(a.getStatus())) {
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
- 확신도: 확실
- 비고: 과제 존재 검사는 컨트롤러와 서비스에 두 번 있고 메시지도 다르다(컨트롤러는 id 를 넣지 않음). 상태 검사는 `D` 만 한다. `R`, `C`, 빈 값, 알 수 없는 코드는 통과한다(선택 목록과의 차이는 BR-03). 상태 비교에 `trim` · 대문자 변환이 없다. 재배포(BR-27)는 한다.

**BR-12**
- 규칙: 과제 마감 시각이 현재 시각보다 이전이면 새 배포를 거부하고, 과제 상태가 `'X'` 이면 허용한다.
- 근거: `J/web/DistributionController.java:59-64`, 화면 `T/distribution_form.html:13,16`
- 근거 코드:
  ```java
  // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
      if (!"X".equals(a.getStatus())) {
  ```
- 확신도: 확실
- 비고:
  - 서비스 `distribute()`(`J/service/DistributionService.java:47-68`)에는 이 검사가 없다. 컨트롤러를 거치지 않으면 우회된다.
  - `isBefore` 라서 마감 시각과 현재 시각이 같으면 허용한다.
  - 마감 시각이 NULL 이면 검사하지 않는다. 스키마는 NOT NULL 이다(`S:86`).
  - 화면에서는 같은 조건의 과제 옵션을 `disabled` 로 만든다(`T/distribution_form.html:16`).
  - 화면 주석 "마감 지난 과제는 선택 불가 (연장 상태 제외)"(`T/distribution_form.html:13`)는 `isBefore` 와 어긋나지 않는다. 마감 정각은 아직 "지난" 것이 아니므로 선택할 수 있다.

**BR-13**
- 규칙: 같은 과제와 같은 학급의 배포가 이미 1건 이상 있으면 새 배포를 거부한다.
- 근거: `J/dao/DistributionDao.java:46-50`, `J/service/DistributionService.java:59-60`
- 근거 코드:
  ```java
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
      throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  ```
- 확신도: 확실
- 비고: DB 에 (`assignment_id`, `class_id`) UNIQUE 제약이 없어 애플리케이션에서 막는다(주석 `J/dao/DistributionDao.java:46`, `S:92-103`). 동시 요청 두 건이 모두 통과할 수 있는지는 확인하지 않았다. 시드 데이터에는 과제 2 → 1반 배포가 두 건(배포 3 · 4) 있다(`db/mariadb/init/02-seed.sql:128,133-134`). 이 규칙이 생기기 전에 재배포가 새 배포 행을 만들던 이력으로 보인다(시드 주석으로 짐작).

**BR-14**
- 규칙: 새 배포의 id 는 `distribution` 테이블의 최대 id + 1 이고, 테이블이 비어 있으면 1 이다.
- 근거: `J/dao/DistributionDao.java:54-56`, `J/service/DistributionService.java:62`
- 근거 코드:
  ```java
  Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
  return (max == null ? 0L : max.longValue()) + 1L;
  ```
- 확신도: 확실
- 비고: `distribution.id` 에 AUTO_INCREMENT 가 없다(`S:93`).

**BR-15**
- 규칙: 새 배포는 배포 시각을 `AppClock.now()` 로, 재배포 플래그를 0 으로 저장하고, 저장 건수가 1 이 아니면 실패로 처리한다.
- 근거: `J/service/DistributionService.java:63-66`, `J/dao/DistributionDao.java:61`
- 근거 코드:
  ```java
  int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
  if (n != 1) {
  ```
- 확신도: 확실
- 비고: `AppClock.now()` 는 기본이 고정 시각이다(BR-62).

**BR-63**
- 규칙: 새 배포는 과제 존재 → 마감 경과 → 학급 존재 → 삭제 상태 → 중복 배포 순서로 검사하고, 앞 검사에서 걸리면 그 메시지 하나만 보여 준다.
- 근거: `J/web/DistributionController.java:53-67`, `J/service/DistributionService.java:48-60`
- 근거 코드:
  ```java
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
  ...
  long id = distributionService.distribute(assignmentId, classId);
  ```
- 확신도: 확실
- 비고: 마감 검사가 컨트롤러에 있어 서비스 검사보다 먼저다. 마감이 지난 삭제(`D`) 과제는 "삭제" 대신 "마감" 메시지를 받는다. 학급이 없어도 마감이 지났으면 마감 메시지가 나온다. 서비스 예외(`IllegalArgumentException`, `IllegalStateException`)와 DB 예외는 flash 오류로 바꿔 새 배포 폼으로 돌려보낸다(`J/web/DistributionController.java:69-74`).

### 2.3 재배포 입력값 정리

**BR-16**
- 규칙: 작업자 ID 가 NULL 이거나 공백뿐이면 `"SYSTEM"` 으로 기록한다.
- 근거: `J/service/DistributionService.java:82-89`
- 근거 코드:
  ```java
  if (op.length() == 0) {
      op = "SYSTEM";
  ```
- 확신도: 확실
- 비고: 작업자 ID 는 DB 에 저장되지 않는다. 표준출력 감사 로그(`:97,693`)와 요약(`:667`)에만 쓴다. 화면은 `operator` 를 `teacher-01` 로 고정해 보낸다(`T/distributions.html:33`).

**BR-17**
- 규칙: 작업자 ID 가 20자를 넘으면 앞 20자로 자르고 경고를 남긴다.
- 근거: `J/service/DistributionService.java:90-92`
- 근거 코드:
  ```java
  } else if (op.length() > 20) {
      op = op.substring(0, 20);
  ```
- 확신도: 확실
- 비고: 20 은 매직 넘버다. `class.teacher_id` 컬럼 길이 `VARCHAR(20)`(`S:78`)와 같다.

**BR-18**
- 규칙: 작업자 ID 가 `SYSTEM` 이 아니고 `teacher-` 나 `admin-` 로 시작하지 않으면 경고만 남기고 진행한다.
- 근거: `J/service/DistributionService.java:94-95`
- 근거 코드:
  ```java
  if (!op.equals("SYSTEM") && !op.startsWith("teacher-") && !op.startsWith("admin-")) {
      warnings.add("작업자 ID 형식이 표준(teacher-, admin-)과 다릅니다: " + op);
  ```
- 확신도: 확실
- 비고: 거부하지 않는다. 20자 자르기(BR-17) 뒤에 검사한다.

**BR-19**
- 규칙: 재배포 사유는 앞뒤 공백을 지우고, 줄바꿈 · 탭을 공백으로 바꾸고, 연속 공백을 하나로 줄인다.
- 근거: `J/service/DistributionService.java:99-107`
- 근거 코드:
  ```java
  rsn = rsn.replace("\r", " ").replace("\n", " ").replace("\t", " ");
  while (rsn.contains("  ")) {
      rsn = rsn.replace("  ", " ");
  ```
- 확신도: 확실
- 비고: `trim` 을 치환 전에 한다(`:103`). 사유 끝에 줄바꿈이 있으면 공백 하나가 남을 수 있다. 사유는 DB 에 저장되지 않고 결과 메시지(`:652`)에만 들어간다.

**BR-20**
- 규칙: 정리한 재배포 사유가 비어 있으면 `"(사유 없음)"` 으로 바꾼다.
- 근거: `J/service/DistributionService.java:108-110`
- 근거 코드:
  ```java
  if (rsn.length() == 0) {
      rsn = "(사유 없음)";
  ```
- 확신도: 확실

**BR-21**
- 규칙: 재배포 사유가 200자를 넘으면 앞 197자에 `"..."` 을 붙여 200자로 만들고 경고를 남긴다.
- 근거: `J/service/DistributionService.java:111-113`
- 근거 코드:
  ```java
  } else if (rsn.length() > 200) {
      rsn = rsn.substring(0, 197) + "...";
  ```
- 확신도: 확실
- 비고: 길이 검사가 꺾쇠 치환(BR-22)보다 먼저라서 치환 뒤에는 200자를 넘을 수 있다.

**BR-22**
- 규칙: 재배포 사유에 `<` 나 `>` 가 있으면 `&lt;`, `&gt;` 로 바꾼다.
- 근거: `J/service/DistributionService.java:115-117`
- 근거 코드:
  ```java
  if (rsn.indexOf('<') >= 0 || rsn.indexOf('>') >= 0) {
      rsn = rsn.replace("<", "&lt;").replace(">", "&gt;");
  ```
- 확신도: 확실
- 비고: 메시지는 `th:text` 로 출력된다(`T/layout.html:29`). Thymeleaf 가 다시 이스케이프하면 화면에 `&lt;` 가 글자 그대로 보일 수 있다(실행해 확인하지 않음).

**BR-23**
- 규칙: 재배포 사유는 포함된 키워드로 분류하고 앞 조건이 우선한다: `오류`·`장애` → 1(시스템 오류), `연장`·`추가` → 2(기간 연장), `정정`·`수정` → 3(문항 정정), `재시험`·`재평가` → 4(재평가), `(사유 없음)` → 9, 그 외 → 0(기타).
- 근거: `J/service/DistributionService.java:119-150`
- 근거 코드:
  ```java
  if (rsn.contains("오류") || rsn.contains("장애")) {
      reasonCategory = 1;
  } else if (rsn.contains("연장") || rsn.contains("추가")) {
  ```
- 확신도: 확실
- 비고:
  - 부분 문자열 일치다. "오류 수정" 은 1, "추가 재평가" 는 2 가 된다.
  - 분류 1 과 4 만 뒤의 판정을 바꾼다(BR-39, BR-57). 2 · 3 · 9 · 0 은 메모 문구만 다르다. 분류 2 "기간 연장" 은 과제 상태 `X`(연장)와 관계없다.
  - 숫자 1 · 2 · 3 · 4 · 9 는 상수 없이 쓴 매직 넘버다.
  - 분류는 200자로 자른 뒤의 사유로 한다(BR-67).

**BR-67**
- 규칙: 재배포 사유 분류(BR-23)는 200자 자르기(BR-21)와 꺾쇠 치환(BR-22)을 거친 뒤의 사유로 한다. 197번째 글자 뒤에 있던 키워드는 분류에 반영되지 않는다.
- 근거: `J/service/DistributionService.java:111-113,119-121`
- 근거 코드:
  ```java
  } else if (rsn.length() > 200) {
      rsn = rsn.substring(0, 197) + "...";
  ...
  int reasonCategory = 0;
  if (rsn.contains("오류") || rsn.contains("장애")) {
  ```
- 확신도: 확실
- 비고: 사유가 길면 뒤쪽에 적은 "오류" · "재평가" 가 빠져 분류가 1 · 4 가 되지 않을 수 있다. 그러면 두 번째 재배포 허용(BR-39)과 재제출 대상(BR-57)이 달라진다.

### 2.4 재배포 대상 판정

**BR-24**
- 규칙: 배포가 없으면 재배포를 거부한다(E101).
- 근거: `J/service/DistributionService.java:153-159`
- 근거 코드:
  ```java
  if (dist == null) {
      result.setOk(false);
      result.setCode("E101");
  ```
- 확신도: 확실
- 비고: 과제 · 학급이 없는 배포도 조회되지 않아 여기서 E101 이 된다(BR-05).

**BR-25**
- 규칙: 과제 상태가 `'D'` 이면 재배포를 거부한다(E103).
- 근거: `J/service/DistributionService.java:198-204`
- 근거 코드:
  ```java
  case "D":
      statusLabel = "삭제";
      result.setOk(false);
  ```
- 확신도: 확실
- 비고: `D` 과제의 배포는 목록에서 빠지므로(BR-04) 화면에서는 재배포 버튼에 닿을 수 없다. URL 로 직접 POST 할 때만 이 분기를 탄다.

**BR-26**
- 규칙: 과제 상태가 `'R'` 이면 재배포를 거부한다(E104).
- 근거: `J/service/DistributionService.java:194-196,215-220`
- 근거 코드:
  ```java
  if (underReview) {
      result.setOk(false);
      result.setCode("E104");
  ```
- 확신도: 확실
- 비고: 새 배포 쪽 서버 검증은 `R` 을 막지 않는다(BR-11). 선택 목록만 막는다(BR-03).

**BR-27**
- 규칙: 재배포에서 과제 상태 코드는 앞뒤 공백을 지우고 대문자로 바꾼 뒤 판정하며, 빈 값이나 알 수 없는 코드는 진행중(`O`)으로 간주하고 경고를 남긴다.
- 근거: `J/service/DistributionService.java:172-176,205-212`
- 근거 코드:
  ```java
  st = st.trim().toUpperCase();
  ...
  warnings.add("알 수 없는 과제 상태 코드: " + st + " (진행중으로 간주)");
  ```
- 확신도: 확실
- 비고: 새 배포 쪽 비교(`"D".equals(...)`, `"X".equals(...)`)와 화면 비교에는 이 정리가 없다. 소문자 `d` 는 새 배포에서 통과하고 재배포에서는 삭제로 판정된다.

**BR-28**
- 규칙: 과제 상태가 `'C'`(종료)여도 재배포를 막지 않고 마감 판정(BR-32 ~ BR-36)에 맡긴다.
- 근거: `J/service/DistributionService.java:190-192,222-225,326-328`
- 근거 코드:
  ```java
  if (closedByTeacher && !extended) {
      // 종료 처리된 과제라도 마감 후 3일 안이면 아래 마감 판정에 맡긴다
  ```
- 확신도: 확실
- 비고: 주석의 "3일" 은 코드의 72시간(BR-36)과 맞는다. `extended` 와 `closedByTeacher` 는 동시에 참이 될 수 없어 `!extended` 는 항상 참이다.

**BR-29**
- 규칙: 배포 대상 학급이 없으면 경고를 남기고 재배포를 계속한다.
- 근거: `J/service/DistributionService.java:242-245`
- 근거 코드:
  ```java
  if (cls == null) {
      className = "(삭제된 학급 #" + dist.getClassId() + ")";
  ```
- 확신도: 추정
- 비고: 코드는 있지만 도달할 수 없다(BR-05). 배포를 읽는 `findById` 가 ``JOIN `class` c ON c.id = d.class_id``(`J/dao/DistributionDao.java:29,39`)라 학급이 없으면 배포 자체가 `null` 이 되어 E101 로 끝난다. 과제가 없을 때의 E102(`J/service/DistributionService.java:164-171`)도 같은 이유로 도달하지 않는다. 두 FK(`S:101-102`)가 있어 참조되는 과제 · 학급 행은 지울 수도 없다. 경고 문구의 "알림은 발송되지 않습니다" 에 해당하는 알림 발송 코드는 모듈 어디에도 없다 → 주석만 있음(6절).

**BR-30**
- 규칙: 학급 담당 교사 ID 가 비었거나, `teacher-` 로 시작하지 않거나, 길이가 10 이 아니면 경고만 남긴다.
- 근거: `J/service/DistributionService.java:251-258`
- 근거 코드:
  ```java
  } else if (!teacherId.startsWith("teacher-")) {
  ...
  } else if (teacherId.length() != 10) {
  ```
- 확신도: 확실
- 비고: 10 = `teacher-` 8자 + 2자. 화면 고정값 `teacher-01`(`T/distributions.html:33`)이 이 길이다.

**BR-31**
- 규칙: 작업자가 `SYSTEM` 이 아니고, 담당 교사와 다르고, `admin-` 으로 시작하지 않으면 "담당 교사가 아닌 작업자의 재배포" 메모만 남기고 진행한다.
- 근거: `J/service/DistributionService.java:259-261`
- 근거 코드:
  ```java
  if (!op.equals("SYSTEM") && teacherId.length() > 0 && !op.equals(teacherId) && !op.startsWith("admin-")) {
      notes.add("담당 교사(" + teacherId + ")가 아닌 작업자(" + op + ")의 재배포");
  ```
- 확신도: 확실
- 비고: 권한 검사가 아니다. 다른 교사도 재배포할 수 있다. `notes` 는 컨트롤러가 화면에 넘기지 않는다(`J/web/DistributionController.java:91-98`).

### 2.5 마감 판정과 재배포 허용

**BR-32**
- 규칙: 과제에 마감 시각이 없으면 마감 규칙을 적용하지 않고 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:269-273,310-311`
- 근거 코드:
  ```java
  if (dueBucket == -1) {
      allowed = true;
  ```
- 확신도: 확실
- 비고: 스키마는 `due_at` NOT NULL 이다(`S:86`). 이미 재배포된 건의 제한(BR-39)은 이 경우에도 적용된다.

**BR-33**
- 규칙: 현재 시각이 마감 시각 이전이거나 같으면 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:284-289,312-313`
- 근거 코드:
  ```java
  } else if (minutesAfterDue == 0) {
      dueBucket = 2;
  ...
  } else if (dueBucket <= 2) {
  ```
- 확신도: 확실
- 비고: 분 단위 차이(`toMinutes`)로 판정한다. `toMinutes` 는 0 쪽으로 버리므로 마감 후 59초까지는 0분이라 "정각"이다(`J/service/DistributionService.java:277,287-289`).

**BR-34**
- 규칙: 과제 상태가 `'X'`(연장)이면 마감 후 경과 시간과 관계없이 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:314-316`
- 근거 코드:
  ```java
  } else if (extended) {
      allowed = true;
      notes.add("상태 '연장' → 마감 경과에도 재배포 허용");
  ```
- 확신도: 확실
- 비고: 연장 기한 같은 상한은 없다.

**BR-35**
- 규칙: 마감 판정 구간은 남은 시간 72시간 초과 / 24 ~ 72시간 / 24시간 이내 · 정각 / 마감 후 72시간 미만(유예) / 72시간 ~ 7일 / 7 ~ 30일 / 30일 이상으로 나눈다.
- 근거: `J/service/DistributionService.java:278-302`
- 근거 코드:
  ```java
  if (minutesAfterDue < -72 * 60) {
  ...
  } else if (hoursAfterDue < 72) {
  ```
- 확신도: 확실
- 비고: 구간 번호(0 ~ 6)는 허용 판정에서 "2 이하"(BR-33)와 "3 이상"(`:326`)으로만 쓴다. 7일 · 30일 경계는 문구만 바꾼다. 마지막 구간의 "개월" 은 30일 = 1개월로 계산한다(`:301`).
  - 구간 2의 문구 "마감 당일"은 달력상 같은 날이 아니라 **마감 전 24시간 이내**를 뜻한다. 마감 뒤에는 같은 날이어도 1분 이상 지나면 구간 3이다.
    ```java
    } else if (minutesAfterDue < 0) {
        dueBucket = 2;
        dueText = "마감 당일 (" + (-minutesAfterDue) + "분 남음)";
    ```
    (`J/service/DistributionService.java:284-286`)

**BR-36**
- 규칙: 위 BR-32 ~ BR-34 에 해당하지 않고 마감 후 경과 시간(시간 단위, 버림)이 72 이상이면 재배포를 거부하고, 72 미만이면 허용한다.
- 근거: `J/service/DistributionService.java:317-325`
- 근거 코드:
  ```java
  // 재배포는 마감 후 7일 이내에만 허용한다 (교무 지침 §4.2)
  if (hoursAfterDue >= 72) {
      allowed = false;
  ```
- 확신도: 확실
- 비고:
  - **주석과 코드가 다르다.** 주석은 7일, 코드는 72시간(3일)이다. 규칙은 코드 기준으로 적었다. 같은 파일의 다른 주석(`:223`)과 구간 문구 "유예 기간"(`:292`), "유예 종료"(`:295`)는 72시간 쪽과 맞는다. "교무 지침 §4.2" 는 저장소에서 찾지 못했다.
  - `toHours()` 는 버림이다. 마감 후 71시간 59분은 허용되고 72시간 0분은 거부된다.
  - 거부 문구는 "과제 상태를 '연장'으로 바꾼 뒤 다시 시도하세요" 인데, 이 모듈에는 과제 상태를 바꾸는 기능이 없다.

**BR-37**
- 규칙: 재배포 결과는 성공이면 메시지와 경고 목록을, 실패면 메시지만 화면에 보여 주고 메모(`notes`) · 결과 코드 · 요약은 보여 주지 않는다.
- 근거: `J/web/DistributionController.java:91-98`, 표시 `T/layout.html:28-33`
- 근거 코드:
  ```java
  if (r.isOk()) {
      ra.addFlashAttribute("message", r.getMessage());
      if (!r.getWarnings().isEmpty()) {
  ```
- 확신도: 확실
- 비고: 사유 분류(BR-23), 마감 판정 문구(BR-35), 등급 분포 등 `notes` 에 쌓는 내용은 화면 어디에도 나오지 않는다. 표준출력 감사 로그에도 `notes` 는 찍지 않는다(`J/service/DistributionService.java:693-697`).

**BR-38**
- 규칙: 거부 코드는 해당 배포가 이미 재배포된 상태면 E106, 아니면 E105 다.
- 근거: `J/service/DistributionService.java:339-341`
- 근거 코드:
  ```java
  result.setCode(dist.getRedistributed() == 1 ? "E106" : "E105");
  ```
- 확신도: 확실
- 비고: 코드는 거부 **이유**가 아니라 재배포 **이력**으로 정해진다. 이미 재배포된 건이 유예 기간 경과(BR-36)로 거부돼도 E106 이다.

**BR-39**
- 규칙: 이미 재배포된(`redistributed = 1`) 배포는 과제 상태가 `'X'` 이거나 사유 분류가 1(오류 · 장애)일 때만 다시 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:329-337`, 화면 `T/distributions.html:30-32`
- 근거 코드:
  ```java
  if (allowed && dist.getRedistributed() == 1) {
      if (extended) {
      ...
      } else if (reasonCategory == 1) {
  ```
- 확신도: 확실
- 비고: 조건이 `allowed && ...` 라서 앞 판정을 어떤 이유로 통과했든 적용된다. 마감 NULL(BR-32), 정각 이전(BR-33), 유예 72시간 미만(BR-36) 모두 해당한다. 연장(`X`)은 여기서도 허용된다. **화면과 서버가 다르다.** 재배포 버튼은 `redistributed == 0 or assignmentStatus == 'X'` 일 때만 나온다(`T/distributions.html:32`). 사유가 "오류"인 두 번째 재배포는 서버는 허용하지만 화면에서는 버튼이 없어 할 수 없다. 재배포 횟수 상한은 없다.

**BR-64**
- 규칙: 재배포는 배포 존재 → 과제 존재 → 삭제 상태 → 검수중 상태 → 마감 · 연장 → 재배포 이력 → 플래그 갱신 순서로 판정하고, 처음 걸린 곳의 코드(E101 ~ E107)로 끝낸다.
- 근거: `J/service/DistributionService.java:153-160,164-171,198-204,215-221,307-345,620-627`
- 근거 코드:
  ```java
  if (!allowed) {
      result.setOk(false);
      result.setCode(dist.getRedistributed() == 1 ? "E106" : "E105");
  ```
- 확신도: 확실
- 비고: 사유 · 작업자 입력 정리(`:81-150`)는 판정보다 먼저 하지만 재배포를 막지 않는다. 제출 현황 계산(`:347-617`)은 허용이 결정된 뒤에만 한다. 판정 순서와 조합은 0.3절 표에 정리했다.

**BR-65**
- 규칙: 배포 목록의 재배포 버튼은 재배포 이력이 없거나 과제 상태가 `'X'` 인 행에만 보이고, 마감 경과 여부와 검수중 · 사유 조건은 보지 않는다.
- 근거: `T/distributions.html:30-32`
- 근거 코드:
  ```html
  <!-- 재배포 버튼: 아직 재배포 안 했거나, 연장 상태인 과제만 -->
  th:if="${d.redistributed == 0 or d.assignmentStatus == 'X'}">
  ```
- 확신도: 확실
- 비고: 주석과 코드가 일치한다. 유예가 지난 배포(서버 E105)와 검수중 과제(서버 E104)에도 버튼이 보인다. 반대로 서버가 허용하는 "이력 있음 + 사유 오류"(BR-39)에는 버튼이 없다.

### 2.6 제출 현황 판정 (재배포 결과 메시지용)

**BR-40**
- 규칙: 제출 행은 제출 시각과 점수로 분류한다: 둘 다 없으면 미제출, 점수만 있으면 채점완료, 제출 시각만 있으면 채점전, 둘 다 있으면 채점완료.
- 근거: `J/service/DistributionService.java:407-422`
- 근거 코드:
  ```java
  } else if (at == null && sc != null) {
      // 제출 시각 없이 점수만 있는 이상 데이터 : 채점된 것으로 본다
      graded++;
  ```
- 확신도: 확실
- 비고: 스키마는 `submitted_at` NOT NULL 이라(`S:109`) 미제출 행과 점수만 있는 행은 스키마상 생길 수 없다. 코드는 "제출 행이 없는 학생"을 미제출로 세지 않는다. 학생 명단 테이블이 없다(ERD.md 5절).

**BR-41**
- 규칙: 제출 시각이 마감 시각보다 뒤면 지연 제출로 센다.
- 근거: `J/service/DistributionService.java:428-434`
- 근거 코드:
  ```java
  if (due != null && at.isAfter(due)) {
      late++;
  ```
- 확신도: 확실
- 비고: 마감 시각과 같으면 지연이 아니다. 지연 제출은 메모 문구에만 쓰이고 점수 · 재제출 대상에 영향이 없다.

**BR-42**
- 규칙: 제출 시각이 현재 시각보다 뒤면 경고를 남긴다.
- 근거: `J/service/DistributionService.java:424-427`
- 근거 코드:
  ```java
  if (at.isAfter(now)) {
      futureSubmit++;
  ```
- 확신도: 확실
- 비고: 현재 시각이 기본으로 2026-09-15 고정이라(BR-62) 그 뒤 날짜의 제출은 모두 경고 대상이다.

**BR-43**
- 규칙: 학생 ID 가 비었거나, `STU-` 로 시작하지 않거나, 길이가 8 이 아니거나, 5번째 글자부터가 숫자가 아니면 형식 오류로 세고 경고만 남긴다.
- 근거: `J/service/DistributionService.java:377-398,506-507`
- 근거 코드:
  ```java
  } else if (!sid.startsWith("STU-")) {
  ...
  } else if (sid.length() != 8) {
  ```
- 확신도: 확실
- 비고: 스키마 주석 "STU-1001 형식"(`S:108`)과 맞는다. 형식이 틀려도 집계에서 빼지 않는다. 경고 문구에 학생 ID 를 그대로 넣고(`:415,426,445`) 표준출력으로 찍는다(`:696`). 이관 시 CLAUDE.md 로깅 규칙("학생 식별자를 로그에 찍지 않는다")과 충돌한다.

**BR-44**
- 규칙: 같은 학생 ID(앞뒤 공백 제거 후)의 제출이 두 번째부터 나오면 중복으로 세고 경고를 남긴다.
- 근거: `J/service/DistributionService.java:399-403,503-504`
- 근거 코드:
  ```java
  if (seenStudents.contains(sid)) {
      dupStudent++;
  ```
- 확신도: 확실
- 비고: 중복 행도 합계 · 평균 · 재제출 대상에 모두 들어간다. 빈 학생 ID 는 행 id 가 붙은 자리표시자로 바뀌어(`:380`) 중복으로 잡히지 않는다.

**BR-45**
- 규칙: 점수가 0 미만이거나 100 초과이면 범위 이탈 경고를 남기지만 합계 · 최저 · 최고 · 등급 집계에는 그대로 넣는다.
- 근거: `J/service/DistributionService.java:443-453`
- 근거 코드:
  ```java
  if (sc.compareTo(BigDecimal.ZERO) < 0 || sc.compareTo(new BigDecimal("100")) > 0) {
      scoreOutOfRange++;
  ```
- 확신도: 확실
- 비고: 만점을 100 으로 가정한다(`:454`). `DECIMAL(5,1)`(`S:110`)은 최대 9999.9 까지 들어간다.

**BR-46**
- 규칙: 점수가 90 이상이면 A, 80 이상 B, 70 이상 C, 60 이상 D, 그 밖에는 F 등급이고, F 는 "60점 미만"으로도 센다.
- 근거: `J/service/DistributionService.java:458-474,547-548`
- 근거 코드:
  ```java
  if (sc.compareTo(new BigDecimal("90")) >= 0) {
      bandA++;
  ```
- 확신도: 확실
- 비고: 등급 경계 90/80/70/60 은 매직 넘버다. 등급은 메시지 · 메모에만 쓰고 저장하지 않는다.

**BR-47**
- 규칙: 평균 점수는 점수가 있는 제출만으로 계산하고 소수 첫째 자리까지 반올림(HALF_UP)한다.
- 근거: `J/service/DistributionService.java:537-539`
- 근거 코드:
  ```java
  BigDecimal avg = sum.divide(new BigDecimal(graded), 1, RoundingMode.HALF_UP);
  ```
- 확신도: 확실

**BR-48**
- 규칙: 제출률은 (전체 제출 행 − 미제출 행) ÷ 전체 제출 행 × 100 을 정수로 반올림한다.
- 근거: `J/service/DistributionService.java:522-523`
- 근거 코드:
  ```java
  int submittedAny = total - notSubmitted;
  int rate = total == 0 ? 0 : (int) Math.round(submittedAny * 100.0 / total);
  ```
- 확신도: 확실
- 비고: 분모가 학급 인원이 아니라 제출 행 수다. 분자는 목록 화면의 "제출 수"(BR-06)와 정의가 다르다. 100% / 80% 이상 / 50% 이상은 메모, 50% 미만 · 0% 는 경고다(`:525-535`).

**BR-49**
- 규칙: 평균이 40 미만이면 "문항 오류 가능성" 경고를 남기고, 평균이 95 초과이면서 채점 건수가 5 이상이면 메모를 남긴다.
- 근거: `J/service/DistributionService.java:554-558`
- 근거 코드:
  ```java
  if (avg.compareTo(new BigDecimal("40")) < 0) {
      warnings.add("평균 40점 미만 → 문항 오류 가능성. 사유를 '문항 정정' 으로 남기는 것을 권장");
  ```
- 확신도: 확실
- 비고: 판정을 바꾸지 않는다. 경고 건수가 결과 코드에만 영향을 준다(BR-60).

**BR-50**
- 규칙: F 등급 수가 채점 건수 ÷ 2(정수 나눗셈)보다 크면 경고를 남긴다.
- 근거: `J/service/DistributionService.java:559-560`
- 근거 코드:
  ```java
  if (bandF > graded / 2) {
      warnings.add("채점자 절반 이상이 F");
  ```
- 확신도: 확실
- 비고: **문구와 코드가 다르다.** 문구는 "절반 이상", 코드는 "초과"다. 채점 4건 중 F 2건(정확히 절반)은 경고하지 않는다. 채점 3건 중 F 2건은 경고한다.

**BR-51**
- 규칙: 채점 건수가 3 이상이고 모두 같은 등급이면 메모를 남긴다.
- 근거: `J/service/DistributionService.java:582-586`
- 근거 코드:
  ```java
  if (e.getValue() != null && e.getValue().intValue() == graded && graded >= 3) {
  ```
- 확신도: 확실

**BR-52**
- 규칙: 첫 제출 시각이 마감 시각보다 뒤면(모든 제출이 마감 후면) 경고를 남긴다.
- 근거: `J/service/DistributionService.java:572-574`
- 근거 코드:
  ```java
  if (due != null && firstSubmit.isAfter(due)) {
      warnings.add("모든 제출이 마감 이후에 이뤄졌습니다.");
  ```
- 확신도: 확실

### 2.7 제출 유지 정책 · 재배포 반영 · 결과

**BR-53**
- 규칙: 재배포는 제출 데이터와 배포 시각을 바꾸지 않고 `distribution.redistributed` 만 1 로 바꾼다.
- 근거: `J/service/DistributionService.java:483-484,620`, `J/dao/DistributionDao.java:65-66`
- 근거 코드:
  ```java
  // 재배포는 기존 제출을 지우지 않는다. 미제출자만 '재제출 대상' 으로 계산해 화면에 알려 준다.
  ...
  return jdbc.update("UPDATE distribution SET redistributed = 1 WHERE id = ?", id);
  ```
- 확신도: 확실
- 비고: 모듈 전체의 쓰기는 이 UPDATE 와 새 배포 INSERT 2곳뿐이다(ERD.md 3절). 사유 · 작업자 · 재배포 시각은 저장하지 않는다. 시드 데이터는 재배포를 새 배포 행(배포 4, `redistributed = 1`)과 그 아래 새 제출 행으로 표현한다(`db/mariadb/init/02-seed.sql:134,158-161`). 지금 코드와 방식이 다르다.

**BR-54**
- 규칙: 플래그 갱신 건수가 0 이면 재배포를 실패(E107)로 처리한다.
- 근거: `J/service/DistributionService.java:620-626`
- 근거 코드:
  ```java
  if (updated == 0) {
      result.setOk(false);
      result.setCode("E107");
  ```
- 확신도: 확실
- 비고: 예외를 던지지 않고 결과 객체를 돌려준다. `@Transactional`(`:71`)은 롤백하지 않는다. 다른 쓰기가 없어 영향은 없다. 이미 1 인 행을 다시 1 로 UPDATE 할 때 MariaDB JDBC 가 돌려주는 건수(affected / matched)에 따라 두 번째 재배포가 E107 이 될 수 있는지는 확인하지 않았다.

**BR-55**
- 규칙: 재제출 대상 수는 기본으로 미제출 수다.
- 근거: `J/service/DistributionService.java:486`
- 근거 코드:
  ```java
  int reopen = notSubmitted;
  ```
- 확신도: 확실
- 비고: 제출 행이 0건이면 "학급 전원이 재제출 대상" 메모를 남기지만(`:509-511`) 재제출 대상 수는 0 이고 메시지에도 "재제출 대상 0명" 으로 나온다(`:648`). 문구와 숫자가 다르다. 재제출 대상은 계산해 메시지에 표시할 뿐 저장하지 않는다. 학생에게 다시 열어 주는 코드가 없다.

**BR-56**
- 규칙: 과제 상태가 `'X'` 이면 채점전 제출자도 재제출 대상에 더한다.
- 근거: `J/service/DistributionService.java:487-490`
- 근거 코드:
  ```java
  if (extended) {
      // 연장 상태면 채점 전 제출자도 다시 낼 수 있다
      reopen = notSubmitted + submittedNoScore;
  ```
- 확신도: 확실

**BR-57**
- 규칙: 사유 분류가 4(재시험 · 재평가)이면 모든 제출 행을 재제출 대상으로 하고 기존 점수는 유지한다.
- 근거: `J/service/DistributionService.java:492-495`
- 근거 코드:
  ```java
  if (reasonCategory == 4) {
      // 재평가 사유면 채점 완료자까지 재제출 대상 (기존 점수는 그대로 유지)
      reopen = total;
  ```
- 확신도: 확실
- 비고: BR-56 뒤에 실행되므로 연장 상태와 겹치면 이 값이 이긴다. "점수 유지"는 점수를 건드리는 코드가 없어서 그렇게 되는 것이다.

**BR-58**
- 규칙: 유지되는 기존 제출 수는 전체 제출 행에서 미제출 행을 뺀 수다.
- 근거: `J/service/DistributionService.java:485`
- 근거 코드:
  ```java
  int kept = total - notSubmitted;
  ```
- 확신도: 확실
- 비고: 점수만 있고 제출 시각이 없는 행도 포함한다. 목록 화면의 "제출 수"(BR-06)와 정의가 다르다. 코드가 재조회해 차이를 메모로 남긴다(`:636-638`).

**BR-59**
- 규칙: 재배포 결과 메시지에서 과제 제목이 비어 있으면 `"(제목 없음)"`, 40자를 넘으면 앞 40자 + `"…"` 로 표시하고, 학급 이름이 비어 있으면 `"학급 #id"` 로 표시한다.
- 근거: `J/service/DistributionService.java:226-236,247-250`
- 근거 코드:
  ```java
  } else if (title.length() > 40) {
      title = title.substring(0, 40) + "…";
  ```
- 확신도: 확실

**BR-60**
- 규칙: 재배포가 성공하면 경고가 0건일 때 `OK`, 1 ~ 2건일 때 `OK-W`, 3건 이상일 때 `OK-W<건수>` 를 결과 코드로 한다.
- 근거: `J/service/DistributionService.java:685-691`
- 근거 코드:
  ```java
  } else if (warnings.size() <= 2) {
      result.setCode("OK-W");
  } else {
  ```
- 확신도: 확실
- 비고: 결과 코드는 표준출력 감사 로그에만 나오고 화면에는 나오지 않는다(`J/web/DistributionController.java:91-98`).

**BR-61**
- 규칙: 재배포 요약의 학생 목록은 30명까지 적고 나머지는 "외 n명" 으로 줄인다.
- 근거: `J/service/DistributionService.java:668-681`
- 근거 코드:
  ```java
  if (shown >= 30) {
      summary.append(" 외 ").append(studentLines.size() - shown).append("명");
  ```
- 확신도: 추정
- 비고: `summary` 를 읽는 코드가 없다(컨트롤러는 `message`, `warnings` 만 씀, `J/web/DistributionController.java:91-98`). 쓰이지 않는 규칙이라 "추정" 으로 두었다.

**BR-66**
- 규칙: 재배포 상태 전환 요약은 미제출 → "재제출 대상", 채점전 → 과제 상태 `'X'` 면 "재제출 가능" 아니면 "유지", 채점완료 → 사유 분류 4 면 "재제출 가능(점수 유지)" 아니면 "유지" 로 적고, 해당자가 없으면 "전환 대상 없음" 으로 적는다.
- 근거: `J/service/DistributionService.java:588-617`
- 근거 코드:
  ```java
  if (extended && submittedNoScore > 0) {
  ...
  transition.append("채점전 ").append(submittedNoScore).append("명 → 유지");
  ```
- 확신도: 확실
- 비고:
  - **전환 문구와 재제출 대상 수가 다르다.** 사유 분류 4 이고 상태가 `X` 가 아니면 채점전 제출자를 "유지"로 적지만, 재제출 대상 수(BR-57)에는 들어간다.
  - 이 문구는 `notes` 에 들어가 화면에 나오지 않는다(BR-37). DB 에도 쓰지 않는다(BR-53).
  - 미제출 학생 ID 목록 `reopenList`(`:374,410`)를 만들지만 읽는 곳이 없다. `RedistributeResult` 의 `keptSubmissions` · `reopenTargets` 도 값만 넣고 읽는 곳이 없다(`:512-513`).

### 2.8 기준 시각

**BR-62**
- 규칙: 시스템 속성 `app.clock` 이 `system` 이 아니면 모든 "현재 시각" 판정(마감 경과, 새 배포 시각, 미래 제출)은 `2026-09-15 00:00:00` 고정값을 쓴다.
- 근거: `J/AppClock.java:8-9,14-19`, 테스트 `legacy/assignment-thymeleaf/src/test/java/com/example/assign/AppClockTest.java:12`
- 근거 코드:
  ```java
  if (sys != null && sys.equals("system")) {
      return LocalDateTime.now().withNano(0);
  }
  return LocalDateTime.parse(FIXED, FMT);
  ```
- 확신도: 확실
- 비고: 주석은 "2024-03 QA 기간 중 임시로 고정. 운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)" 이다. 2024-03 에 고정했다는데 값은 2026-09-15 라서 주석과 값이 맞지 않는다. 테스트가 고정값을 검증하므로 되돌리면 테스트가 깨진다.

## 3. 규칙 번호 색인

| 구분 | 규칙 |
|---|---|
| 목록 · 정렬 · 제외 | BR-01 ~ BR-10 |
| 새 배포 등록 검증 | BR-11 ~ BR-15, BR-63 |
| 재배포 입력 정리 | BR-16 ~ BR-23, BR-67 |
| 재배포 대상 판정 | BR-24 ~ BR-31 |
| 마감 판정 · 재배포 허용 · 결과 표시 | BR-32 ~ BR-39, BR-64, BR-65 |
| 제출 현황 판정 | BR-40 ~ BR-52 |
| 제출 유지 · 반영 · 결과 | BR-53 ~ BR-61, BR-66 |
| 기준 시각 | BR-62 |

## 4. 매직 넘버

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `'O'`, `'X'`, `'C'`, `'R'`, `'D'` | `J/dao/AssignmentDao.java:29,36`, `J/dao/DistributionDao.java:33`, `J/web/DistributionController.java:61`, `J/service/DistributionService.java:56,182-198`, `T/assignments.html:21,26`, `T/distribution_form.html:16,18`, `T/distributions.html:32` | 과제 상태: 진행중 · 연장 · 종료 · 검수중 · 삭제. 스키마 주석에는 `O` · `C` 만 있음(`S:87`) |
| `0`, `1` (`redistributed`) | `J/dao/DistributionDao.java:61,66`, `J/service/DistributionService.java:329,341,634,644`, `T/distributions.html:27,32` | 재배포 안 함 / 함 |
| `20` | `J/service/DistributionService.java:90-92` | 작업자 ID 최대 길이 |
| `"SYSTEM"` | `J/service/DistributionService.java:88,94,259` | 작업자 미지정 시 기본값 |
| `"teacher-"`, `"admin-"` | `J/service/DistributionService.java:94,254,259` | 표준 작업자 ID 접두어 |
| `"teacher-01"` | `T/distributions.html:33` | 화면이 보내는 고정 작업자 ID |
| `200`, `197` | `J/service/DistributionService.java:111-113` | 사유 최대 길이, 말줄임 앞 길이(197 + `"..."` 3자 = 200) |
| `1`, `2`, `3`, `4`, `9`, `0` (`reasonCategory`) | `J/service/DistributionService.java:119-149,332,492,497,608` | 사유 분류: 시스템 오류 · 기간 연장 · 문항 정정 · 재평가 · 없음 · 기타 |
| `10` | `J/service/DistributionService.java:256` | 담당 교사 ID 표준 길이(`teacher-` + 2자리) |
| `-72 * 60`, `-24 * 60` | `J/service/DistributionService.java:278,281` | 마감 전 3일 · 1일(분) |
| `72` | `J/service/DistributionService.java:290,319` | 마감 후 유예 기간(시간). 주석은 7일 |
| `24 * 7` | `J/service/DistributionService.java:293` | 마감 후 7일(시간), 문구 구간만 |
| `24 * 30`, `/ 24 / 30` | `J/service/DistributionService.java:296,301` | 30일 = 1개월 |
| `-1` ~ `6` (`dueBucket`) | `J/service/DistributionService.java:271-300,310-312,326` | 마감 판정 구간 번호. `-1` 마감 없음 |
| `"E101"` ~ `"E107"` | `J/service/DistributionService.java:156,167,201,217,341,623` | 재배포 실패 코드: 배포 없음 · 과제 없음 · 삭제 · 검수중 · 유예 경과 · 재배포 제한 · 갱신 실패 |
| `"OK"`, `"OK-W"`, `2` | `J/service/DistributionService.java:685-690` | 성공 코드, 경고 건수 경계 |
| `"STU-"`, `8`, `4` | `J/service/DistributionService.java:381,383,386` | 학생 ID 접두어, 전체 길이, 숫자 시작 위치 |
| `0`, `100` | `J/service/DistributionService.java:443,454` | 점수 범위, 만점 |
| `90`, `80`, `70`, `60` | `J/service/DistributionService.java:458,461,464,467` | 등급 A · B · C · D 하한 |
| `80`, `50` | `J/service/DistributionService.java:527,529` | 제출률 메모 경계(%) |
| `40` | `J/service/DistributionService.java:554` | 문항 오류 의심 평균 점수 |
| `95`, `5` | `J/service/DistributionService.java:556` | 고득점 메모 평균, 최소 채점 건수 |
| `graded / 2` | `J/service/DistributionService.java:559` | F 과반 경고 기준(정수 나눗셈) |
| `3` | `J/service/DistributionService.java:583` | 전원 같은 등급 메모의 최소 채점 건수 |
| `1`, `HALF_UP` | `J/service/DistributionService.java:538` | 평균 소수 자릿수, 반올림 방식 |
| `40` | `J/service/DistributionService.java:234-235` | 메시지의 과제 제목 최대 길이 |
| `30` | `J/service/DistributionService.java:672` | 요약에 적는 학생 최대 수 |
| `"2026-09-15 00:00:00"` | `J/AppClock.java:9` | 고정 현재 시각 |

## 5. 주석 · 문구와 코드의 불일치, 여러 곳에 다르게 구현된 규칙

| 항목 | A | B | 관련 규칙 |
|---|---|---|---|
| 재배포 유예 기간 | 주석 "마감 후 7일 이내" (`J/service/DistributionService.java:318`) | 코드 `hoursAfterDue >= 72` 거부 (`:319`) | BR-36 |
| F 과반 경고 | 문구 "절반 이상" (`J/service/DistributionService.java:560`) | 코드 `bandF > graded / 2` (`:559`) | BR-50 |
| 제출 0건 | 메모 "학급 전원이 재제출 대상" (`J/service/DistributionService.java:510`) | 재제출 대상 수 0 (`:486,648`) | BR-55 |
| 고정 시각 | 주석 "2024-03 QA 기간 중 임시로 고정" (`J/AppClock.java:8`) | 값 `2026-09-15` (`:9`) | BR-62 |
| 과제 상태 `C` | 스키마 주석 `C=마감` (`S:87`) | 코드 · 화면 라벨 "종료" (`J/service/DistributionService.java:191`, `T/assignments.html:26`) | BR-10 |
| 마감 지난 과제의 새 배포 금지 | 컨트롤러에서 검사 (`J/web/DistributionController.java:59-64`), 화면 `disabled` (`T/distribution_form.html:16`) | 서비스 `distribute()` 에 없음 (`J/service/DistributionService.java:47-68`) | BR-12 |
| 새 배포 가능한 과제 상태 | 선택 목록 `IN ('O','X','C')` (`J/dao/AssignmentDao.java:36`) | 서버 검증은 `D` 만 거부 (`J/service/DistributionService.java:56`) | BR-03, BR-11 |
| 과제 존재 검사 | 컨트롤러 (`J/web/DistributionController.java:53-57`) | 서비스 (`J/service/DistributionService.java:48-51`), 메시지 다름 | BR-11 |
| 상태 코드 정규화 | 새 배포 · 화면은 그대로 비교 (`J/web/DistributionController.java:61`, `J/service/DistributionService.java:56`) | 재배포는 `trim().toUpperCase()` (`J/service/DistributionService.java:176`) | BR-27 |
| 두 번째 재배포 허용 | 서버: 연장 **또는** 사유 분류 1 (`J/service/DistributionService.java:329-337`) | 화면 버튼: 미재배포 **또는** 연장 (`T/distributions.html:32`) | BR-39, BR-65 |
| 마감 기준 | 새 배포 · 목록: 초 단위, 마감 지나면 곧바로 (`J/web/DistributionController.java:60`) | 재배포: 72시간 유예, 분 · 시간 단위 (`J/service/DistributionService.java:275-325`) | BR-12, BR-36 |
| 재배포 방식 | 시드: 새 배포 행 + 새 제출 행 (`db/mariadb/init/02-seed.sql:128,134,158-161`) | 코드: 기존 행의 플래그만 1 로 (`J/dao/DistributionDao.java:66`) | BR-13, BR-53 |
| 채점전 제출자의 전환 (사유 분류 4, 상태 ≠ `X`) | 전환 문구 "유지" (`J/service/DistributionService.java:602`) | 재제출 대상 수에 포함 (`:494`) | BR-66 |
| 제출 수 정의 | 목록: `submitted_at IS NOT NULL` (`J/dao/DistributionDao.java:26`) | 재배포: 미제출이 아닌 행(점수만 있는 행 포함) (`J/service/DistributionService.java:485,522`) | BR-06, BR-48, BR-58 |
| NULL 처리 | 스키마: `due_at`, `submitted_at`, `title`, `name`, `teacher_id`, `student_id` 모두 NOT NULL (`S:77-78,84,86,108-109`) | 코드: 각각 NULL 분기 있음 (ERD.md 4절) | BR-32, BR-40 등 |

## 6. 주석 · 문구만 있음 (규칙으로 올리지 않음)

코드로 뒷받침되지 않아 규칙에서 뺀 것이다.

| 내용 | 위치 | 코드 상태 |
|---|---|---|
| 재배포 "마감 후 7일 이내" · "교무 지침 §4.2" | `J/service/DistributionService.java:318` | 코드는 72시간(BR-36). 지침 문서는 저장소에 없음 |
| 학급이 없으면 "알림은 발송되지 않습니다" | `J/service/DistributionService.java:245` | 알림 발송 코드가 모듈에 없음 |
| "과제 상태를 '연장'으로 바꾼 뒤 다시 시도하세요" | `J/service/DistributionService.java:321` | 과제 상태를 바꾸는 기능이 모듈에 없음 |
| "점수 변경이 필요하면 성적 모듈에서 처리하세요" | `J/service/DistributionService.java:498` | 성적 모듈과 연동하는 코드가 없음 |
| "기존 v1.3 코드 그대로 두고 아래에 덧붙임" | `J/service/DistributionService.java:70` | v1.3 코드로 보이는 부분이 파일에 따로 없음 |
| "운영 반영 전 LocalDateTime.now() 로 되돌릴 것" | `J/AppClock.java:8` | 되돌리지 않았고 테스트가 고정값을 검증함(BR-62) |

## 7. 미확인 목록

- 재배포 사유의 `&lt;` 치환 결과가 화면에 어떻게 보이는지(BR-22). 실행하지 않았다.
- 이미 `redistributed = 1` 인 행을 다시 UPDATE 할 때 JDBC 가 돌려주는 건수(BR-54). 드라이버 설정(`useAffectedRows`)에 따라 다르며 확인하지 않았다.
- 중복 배포 검사(BR-13)가 동시 요청에서도 지켜지는지.
- 운영 DB 데이터에 스키마 주석에 없는 상태 코드(`X`, `R`, `D`)와 NULL 값이 실제로 있는지. 시드 데이터에는 상태 `O` · `C` 만 있고 `submitted_at` NULL 행이 없다(`db/mariadb/init/02-seed.sql:119-125,143-181`). 운영 DB 는 보지 않았다.
- 0.3절 "시드 데이터 대입" 표는 코드를 따라가 본 결과다. 앱을 실행해 확인하지 않았다.

## 8. 검증 이력

2026-09-29 — 독립 분석 문서 [CROSS-CHECK.md](CROSS-CHECK.md)(CX-01 ~ CX-31)와 대조하고 어긋난 항목을 코드로 확인했다. 앱은 실행하지 않았다.

- BR-29 · BR-24(E102): CX-24 · CX-09 는 "학급이 없어도 재배포 진행", "과제 없으면 E102"를 도달 가능한 규칙으로 적었다. 코드를 확인한 결과 `findById` 의 INNER JOIN(`J/dao/DistributionDao.java:28-29,39`) 때문에 도달하지 않는다. BR 판정은 유지하고 BR-29 비고에 JOIN · FK 근거를 보탰다.
- BR-39: 두 번째 재배포 제한이 마감 NULL 로 통과한 경우에도 적용된다는 것을 코드 `allowed && dist.getRedistributed() == 1`(`J/service/DistributionService.java:329`)로 확인했다. CX-19 는 "CX-16~18 통과 뒤"라고만 적었다. BR 판정은 유지하고 비고에 적용 범위를 명시했다.
- BR-33 · BR-35: CX-16 의 "마감 당일"은 코드 문구(`J/service/DistributionService.java:286`)이고 마감 전 24시간 이내를 뜻한다. BR 판정(마감 후 59초까지 정각으로 허용)은 유지하고, BR-35 비고에 문구의 뜻을, BR-33 근거에 `:277` 을 보탰다.
- BR-11 · BR-63: 과제 존재 검사가 컨트롤러(`J/web/DistributionController.java:53-57`)와 서비스(`J/service/DistributionService.java:48-51`) 두 곳에 있다는 BR 서술을 확인했다. CX-01 은 서비스만 적었다. 변경 없음.
- BR-15: 근거 줄번호를 `:63-65` 에서 `:63-66` 으로 고쳤다(`throw` 블록 끝까지). CX-05 의 `:62-63` 은 id 생성(BR-14, `:62`)을 포함한 범위라 둘 다 코드와 맞는다.
- BR-67 추가: CX-22 에만 있던 "분류는 자른 뒤의 사유로 한다"를 코드(`J/service/DistributionService.java:111-113,119-121`)로 확인해 규칙으로 올렸다. BR-23 비고에 참조를 달았다.
- BR-12: CX 가 주석-코드 불일치로 든 폼 주석(`T/distribution_form.html:13`)은 `isBefore` 와 어긋나지 않는다고 판단해 불일치 표(5절)에 넣지 않았다. BR-12 비고에 이유를 적었다.
- ARCHITECTURE.md · ERD.md: 위 변경과 겹치는 서술이 없어 고치지 않았다. ERD.md 의 JOIN · FK 서술(72-81줄)은 확인한 코드와 맞다.
