# legacy/assignment-thymeleaf 데이터 구조 (ERD)

> 분석 범위: 이 모듈이 쓰는 테이블, 테이블 관계, 읽기·쓰기 위치. 비즈니스 규칙은 아직 분석하지 않았다.
> 경로 약어: `J/` = `legacy/assignment-thymeleaf/src/main/java/com/example/assign/`,
> `S` = `db/mariadb/init/01-schema.sql`
> 함께 읽을 문서: [ARCHITECTURE.md](ARCHITECTURE.md)

## 0. 기준 스키마

- 이 모듈은 MariaDB `itembank` 에 붙는다.
  - 기본값: `legacy/assignment-thymeleaf/src/main/resources/application.properties:2`
  - compose `thymeleaf` 프로필: `docker-compose.yml:50-52,61`
- compose 는 `db/mariadb/init/` 을 초기화 스크립트로 마운트한다 (`docker-compose.yml:25`).
- 스키마 파일에도 이 모듈이 같은 DB를 쓴다고 적혀 있다 (`S:72-74`).
- 이 문서의 테이블·컬럼 정의는 `S` 가 기준이다. 실제로 떠 있는 DB가 이 파일과 같은지는 미확인이다.

## 1. 테이블 목록

이 모듈의 SQL에 나오는 테이블은 5개다. 같은 DB의 `item`, `tag`, `item_tag` 테이블과 `v_item_public` 뷰(`S:20-70`)는 이 모듈의 SQL에 나오지 않아 제외했다.

| 테이블 | 컬럼 (스키마 정의) | 키 · 제약 | 스키마 근거 | 이 모듈의 SQL 근거 |
|---|---|---|---|---|
| `assignment` | `id` INT, `title` VARCHAR(200), `unit_id` INT, `due_at` DATETIME, `status` CHAR(1) 기본값 `'O'`. 전부 NOT NULL | PK `id`, FK `unit_id` → `unit.id` | `S:82-90` | `J/dao/AssignmentDao.java:22-24,29,36,42`, `J/dao/DistributionDao.java:24,28,33` |
| `unit` | `id` INT, `code` VARCHAR(16), `name` VARCHAR(100), `grade` TINYINT. 전부 NOT NULL | PK `id`, UNIQUE `code` | `S:11-18` | `J/dao/AssignmentDao.java:22,24` (`code`, `name` 만 읽음. `grade` 는 안 씀) |
| `class` | `id` INT, `name` VARCHAR(50), `teacher_id` VARCHAR(20). 전부 NOT NULL | PK `id` | `S:75-80` | `J/dao/ClassDao.java:21,25`, `J/dao/DistributionDao.java:25,29` |
| `distribution` | `id` INT, `assignment_id` INT, `class_id` INT, `distributed_at` DATETIME, `redistributed` TINYINT 기본값 0. 전부 NOT NULL | PK `id`, FK `assignment_id` → `assignment.id`, FK `class_id` → `class.id`, 인덱스 `assignment_id`·`class_id`. (`assignment_id`, `class_id`) UNIQUE 없음 | `S:92-103` | `J/dao/AssignmentDao.java:23`, `J/dao/DistributionDao.java:24-27,49,55,61,66` |
| `submission` | `id` INT, `distribution_id` INT, `student_id` VARCHAR(20), `submitted_at` DATETIME, `score` DECIMAL(5,1) NULL. `score` 만 NULL 허용 | PK `id`, FK `distribution_id` → `distribution.id`, 인덱스 `distribution_id`·`student_id` | `S:105-115` | `J/dao/DistributionDao.java:26,71` |

## 2. 테이블 관계

```mermaid
erDiagram
    UNIT {
        INT id PK
        VARCHAR code UK
        VARCHAR name
        TINYINT grade
    }
    ASSIGNMENT {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at
        CHAR status "스키마 주석: O=진행 C=마감"
    }
    CLASS {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id "참조 테이블 미확인"
    }
    DISTRIBUTION {
        INT id PK
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed
    }
    SUBMISSION {
        INT id PK
        INT distribution_id FK
        VARCHAR student_id "참조 테이블 미확인"
        DATETIME submitted_at
        DECIMAL score "NULL 허용"
    }

    UNIT ||--o{ ASSIGNMENT : "선언 fk_assignment_unit"
    ASSIGNMENT ||--o{ DISTRIBUTION : "선언 fk_distribution_assignment"
    CLASS ||--o{ DISTRIBUTION : "선언 fk_distribution_class"
    DISTRIBUTION ||--o{ SUBMISSION : "선언 fk_submission_distribution"
```

| 관계 | 구분 | FK 선언 | 코드의 JOIN · 조건 |
|---|---|---|---|
| `assignment.unit_id` → `unit.id` | 선언 | `S:89` | `LEFT JOIN unit u ON u.id = a.unit_id` (`J/dao/AssignmentDao.java:24`) |
| `distribution.assignment_id` → `assignment.id` | 선언 | `S:101` | `JOIN assignment a ON a.id = d.assignment_id` (`J/dao/DistributionDao.java:28`), 서브쿼리 `d.assignment_id = a.id` (`J/dao/AssignmentDao.java:23`) |
| `distribution.class_id` → `class.id` | 선언 | `S:102` | ``JOIN `class` c ON c.id = d.class_id`` (`J/dao/DistributionDao.java:29`) |
| `submission.distribution_id` → `distribution.id` | 선언 | `S:114` | 서브쿼리 `s.distribution_id = d.id` (`J/dao/DistributionDao.java:26`), `WHERE distribution_id = ?` (`:71`) |

- 코드의 JOIN과 조건은 모두 선언된 FK와 일치한다. FK 선언 없이 코드에서만 추정한 관계는 없다.
- 카디널리티 `1 : 0..N` 은 FK 방향과 NOT NULL 선언(`S:85,94-95,107`)에서 읽은 것이다.
- `assignment` ↔ `unit` 을 코드는 `LEFT JOIN` 으로 읽는다(`J/dao/AssignmentDao.java:24`). 스키마로는 `unit_id` 가 NOT NULL인 FK라(`S:85,89`) 짝이 없는 행이 생길 수 없다.

## 3. 읽기 · 쓰기 위치 표

"호출하는 곳"은 해당 DAO 함수를 부르는 상위 코드다. 쓰기(INSERT·UPDATE)는 모듈 전체에서 2곳이고 DELETE는 없다(`J/` 아래 SQL 문자열 검색 결과).

### assignment

| 구분 | 파일 · 함수 | 줄 | 조건 · 비고 | 호출하는 곳 |
|---|---|---|---|---|
| SELECT | `J/dao/AssignmentDao.java` `findAllForList()` | 27-32 | `status <> 'D'`, `due_at, id` 오름차순 (`:29-30`) | `J/web/AssignmentController.java:28` |
| SELECT | `J/dao/AssignmentDao.java` `findAllForSelect()` | 34-39 | `status IN ('O','X','C')` (`:36`) | `J/web/DistributionController.java:42` |
| SELECT | `J/dao/AssignmentDao.java` `findById(long)` | 41-48 | `a.id = ?` (`:42`) | `J/web/DistributionController.java:53`, `J/service/DistributionService.java:48,164` |
| SELECT (JOIN) | `J/dao/DistributionDao.java` `findAllForList()` | 31-36 | `title`, `status`, `due_at` 을 읽음 (`:24`), `a.status <> 'D'` (`:33`) | `J/service/DistributionService.java:39` |
| SELECT (JOIN) | `J/dao/DistributionDao.java` `findById(long)` | 38-44 | 위와 같은 `BASE_SELECT` 사용 (`:39`) | `J/service/DistributionService.java:43,153,631` |
| 쓰기 | 없음 | | | |

### unit

| 구분 | 파일 · 함수 | 줄 | 조건 · 비고 | 호출하는 곳 |
|---|---|---|---|---|
| SELECT (LEFT JOIN) | `J/dao/AssignmentDao.java` `findAllForList()`, `findAllForSelect()`, `findById(long)` | 27-48 | `BASE_SELECT` 로 `u.code`, `u.name` 을 읽음 (`:22,24`) | assignment 표의 호출처와 같음 |
| 쓰기 | 없음 | | | |

### class

| 구분 | 파일 · 함수 | 줄 | 조건 · 비고 | 호출하는 곳 |
|---|---|---|---|---|
| SELECT | `J/dao/ClassDao.java` `findAll()` | 20-22 | `id` 오름차순 (`:21`) | `J/web/DistributionController.java:43` |
| SELECT | `J/dao/ClassDao.java` `findById(long)` | 24-30 | `id = ?` (`:25`) | `J/service/DistributionService.java:52,239` |
| SELECT (JOIN) | `J/dao/DistributionDao.java` `findAllForList()`, `findById(long)` | 31-44 | `c.name` 을 읽음 (`:25,29`) | distribution 표의 호출처와 같음 |
| 쓰기 | 없음 | | | |

### distribution

| 구분 | 파일 · 함수 | 줄 | 조건 · 비고 | 호출하는 곳 |
|---|---|---|---|---|
| SELECT (서브쿼리) | `J/dao/AssignmentDao.java` `findAllForList()`, `findAllForSelect()`, `findById(long)` | 27-48 | `COUNT(DISTINCT d.class_id)` 로 과제별 배포 학급 수 계산 (`:23`) | assignment 표의 호출처와 같음 |
| SELECT | `J/dao/DistributionDao.java` `findAllForList()` | 31-36 | `a.status <> 'D'`, `a.due_at, d.id` 오름차순 (`:33-34`) | `J/service/DistributionService.java:39` |
| SELECT | `J/dao/DistributionDao.java` `findById(long)` | 38-44 | `d.id = ?` (`:39`) | `J/service/DistributionService.java:43,153,631` |
| SELECT | `J/dao/DistributionDao.java` `countByAssignmentAndClass(long, long)` | 47-52 | 같은 과제·학급의 건수 (`:49`). UNIQUE 대신 여기서 중복을 막는다는 주석이 있음 (`:46`) | `J/service/DistributionService.java:59` |
| SELECT | `J/dao/DistributionDao.java` `nextId()` | 54-57 | `COALESCE(MAX(id), 0) + 1` (`:55-56`) | `J/service/DistributionService.java:62` |
| **INSERT** | `J/dao/DistributionDao.java` `insert(long, long, long, LocalDateTime)` | 59-63 | `redistributed = 0` 으로 삽입 (`:61`) | `J/service/DistributionService.java:63` (`distribute`) |
| **UPDATE** | `J/dao/DistributionDao.java` `markRedistributed(long)` | 65-67 | `SET redistributed = 1 WHERE id = ?` (`:66`) | `J/service/DistributionService.java:620` (`redistribute`) |

### submission

| 구분 | 파일 · 함수 | 줄 | 조건 · 비고 | 호출하는 곳 |
|---|---|---|---|---|
| SELECT (서브쿼리) | `J/dao/DistributionDao.java` `findAllForList()`, `findById(long)` | 31-44 | `COUNT(*) ... submitted_at IS NOT NULL` 로 배포별 제출 수 계산 (`:26`) | distribution 표의 호출처와 같음 |
| SELECT | `J/dao/DistributionDao.java` `findSubmissions(long)` | 69-73 | `distribution_id = ?`, `id` 오름차순. 5개 컬럼 전부 읽음 (`:71`) | `J/service/DistributionService.java:348` |
| 쓰기 | 없음 | | | |

### 쓰기 흐름 요약

- `POST /distributions` → `DistributionController.create` (`J/web/DistributionController.java:67`) → `DistributionService.distribute` → `countByAssignmentAndClass` → `nextId` → `insert` (`J/service/DistributionService.java:59,62,63`)
- `POST /distributions/{id}/redistribute` → `DistributionController.redistribute` (`J/web/DistributionController.java:86`) → `DistributionService.redistribute` → `findSubmissions` → `markRedistributed` → `findById` 재조회 (`J/service/DistributionService.java:348,620,631`)

## 4. 스키마와 코드가 다른 점

| 항목 | 스키마 | 코드 |
|---|---|---|
| `assignment.status` 값 | 주석에는 `O`=진행, `C`=마감만 있음 (`S:87`) | `D`, `X`, `R` 도 씀 (`J/dao/AssignmentDao.java:29,36`, `J/web/DistributionController.java:61`, `J/service/DistributionService.java:56,181-212`) |
| `assignment.due_at` NULL 여부 | NOT NULL (`S:86`) | NULL을 처리하는 분기가 있음 (`J/dao/AssignmentDao.java:60`, `J/web/DistributionController.java:60`, `J/service/DistributionService.java:269-273`) |
| `submission.submitted_at` NULL 여부 | NOT NULL (`S:109`) | NULL을 "미제출"로 셈 (`J/dao/DistributionDao.java:26,103`, `J/service/DistributionService.java:407-416`) |
| `class.teacher_id`, `class.name`, `assignment.title`, `submission.student_id` NULL 여부 | 모두 NOT NULL (`S:77-78,84,108`) | NULL을 처리하는 분기가 있음 (`J/service/DistributionService.java:227,247,251,377`) |
| 과제·학급 중복 배포 | (`assignment_id`, `class_id`) UNIQUE 없음 (`S:92-103`) | 애플리케이션에서 `COUNT` 로 막음 (`J/dao/DistributionDao.java:46-52`, `J/service/DistributionService.java:59`) |
| `distribution.id` 채번 | `INT NOT NULL`, AUTO_INCREMENT 없음 (`S:93`) | `MAX(id)+1` 로 정함 (`J/dao/DistributionDao.java:54-57`) |
| `id` 타입 | `INT` (`S:83,93,106`, `S:12,76`) | Java에서는 `long` 으로 읽음 (`J/dao/AssignmentDao.java:54`, `J/dao/DistributionDao.java:79,99`, `J/dao/ClassDao.java:36`) |

## 5. 미확인 목록

- 실제로 떠 있는 DB 스키마가 `S` 와 같은지. 운영 DB는 보지 않았다.
- `submission.student_id` 가 가리키는 학생 테이블. `S` 에 학생 테이블이 없다.
- `class.teacher_id` 가 가리키는 교사·사용자 테이블. `S` 에는 없고, `db/mariadb/init/03-users.sql` 은 읽지 않았다.
- `assignment`, `unit`, `class`, `submission` 에 쓰는 주체. 이 모듈에는 쓰기 코드가 없다.
- 시드 데이터(`db/mariadb/init/02-seed.sql`)에 들어 있는 `assignment.status` 값과 NULL·형식 이상 데이터의 실제 존재 여부. 이 파일은 읽지 않았다.
- `J/model/*.java` 의 필드 타입. DAO가 호출하는 setter 이름만 확인했다.
- `db/mssql/` 스키마. 이 모듈은 MariaDB만 쓰므로 보지 않았다.
