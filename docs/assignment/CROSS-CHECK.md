# CROSS-CHECK — legacy/assignment-thymeleaf 비즈니스 규칙 (코드 기준 독립 추출)

- 대상: `legacy/assignment-thymeleaf/src/main` 의 Java · Thymeleaf 템플릿. 외부 라이브러리 폴더와 `docs/` 는 읽지 않았다.
- 범위: 과제 배포 조건, 마감 판정, 재배포 허용 · 차단, 제출 상태 전환.
- 원칙: 코드에 있는 것만 적는다. 주석과 코드가 다르면 코드를 따른다(차이는 §4).
- 경로 약칭: `S` = `src/main/java/com/example/assign/service/DistributionService.java`,
  `C` = `.../web/DistributionController.java`, `AD` = `.../dao/AssignmentDao.java`,
  `DD` = `.../dao/DistributionDao.java`, `AC` = `.../AppClock.java`,
  `T-form` = `src/main/resources/templates/distribution_form.html`, `T-dist` = `.../templates/distributions.html`.

## 1. 신규 배포 조건

### CX-01 존재하지 않는 과제는 배포할 수 없다
- 근거: `S:48-51`
```java
AssignmentRow a = assignmentDao.findById(assignmentId);
if (a == null) {
    throw new IllegalArgumentException("존재하지 않는 과제입니다. (id=" + assignmentId + ")");
```

### CX-02 존재하지 않는 학급에는 배포할 수 없다
- 근거: `S:52-55`
```java
ClassRow c = classDao.findById(classId);
if (c == null) {
    throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
```

### CX-03 상태가 삭제('D')인 과제는 배포할 수 없다
- 근거: `S:56-58`
```java
if ("D".equals(a.getStatus())) {
    throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
```
- 비교는 대소문자 · 공백을 정규화하지 않은 정확 일치다(재배포 쪽은 정규화함, CX-13 참고).

### CX-04 같은 과제를 같은 학급에 두 번 새로 배포할 수 없다
- 근거: `S:59-61`, `DD:46-52`
```java
if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
    throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
```
- DB UNIQUE 제약이 아니라 애플리케이션에서 막는다(`DD:46` 주석 "UNIQUE 제약은 없음, 여기서 막는다").

### CX-05 새 배포는 현재 시각을 배포 시각으로, 재배포 플래그 0 으로 저장된다
- 근거: `S:62-63`, `DD:59-62`
```java
int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
"INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)"
```
- 저장 건수가 1 이 아니면 `IllegalStateException("배포 저장에 실패했습니다.")`(`S:64-66`).

### CX-06 마감 시각이 지난 과제는 새로 배포할 수 없다. 단 상태가 연장('X')이면 예외다
- 근거: `C:58-65`
```java
if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
    if (!"X".equals(a.getStatus())) {
        ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
```
- `isBefore` 라서 마감 시각 정각은 아직 배포 가능하다. 마감 시각이 없으면(`null`) 이 검사를 건너뛴다.
- 이 검사는 컨트롤러에만 있고 `DistributionService.distribute` 에는 없다.

### CX-07 배포 화면의 과제 후보는 상태 O · X · C 만이고, 마감 지난 비연장 과제는 선택 불가로 표시된다
- 근거: `AD:34-38`, `T-form:16`
```java
+ " WHERE a.status IN ('O', 'X', 'C') "
th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
```
- 검수중('R')과 삭제('D') 과제는 후보에 나오지 않는다. 다만 서비스 · 컨트롤러는 'R' 을 막지 않으므로(CX-03 은 'D' 만) 직접 POST 하면 'R' 과제도 배포되는 것으로 읽힌다.

### CX-08 "현재 시각"은 기본적으로 2026-09-15 00:00:00 으로 고정되어 있다
- 근거: `AC:9`, `AC:14-19`
```java
private static final String FIXED = "2026-09-15 00:00:00";
if (sys != null && sys.equals("system")) { return LocalDateTime.now().withNano(0); }
return LocalDateTime.parse(FIXED, FMT);
```
- JVM 시스템 프로퍼티 `app.clock=system` 일 때만 실제 시각을 쓴다. 배포 · 재배포의 모든 마감 판정(CX-06, CX-15~19)이 이 값을 쓴다.

## 2. 재배포 — 허용 · 차단

재배포는 `S:72` `redistribute()` 한 메서드에서 판정하며, 거부는 예외가 아니라 `ok=false` 와 코드(E101~E107)를 담은 결과로 돌려준다.

### CX-09 존재하지 않는 배포는 E101, 배포에 연결된 과제가 없으면 E102 로 거부한다
- 근거: `S:153-160`, `S:164-171`
```java
if (dist == null) { ... result.setCode("E101");
if (asg == null) { ... result.setCode("E102");
```

### CX-10 삭제('D') 과제는 재배포할 수 없다 (E103)
- 근거: `S:198-204`
```java
case "D":
    statusLabel = "삭제";
    result.setCode("E103");
```

### CX-11 검수중('R') 과제는 재배포할 수 없다 (E104)
- 근거: `S:194-197`, `S:215-221`
```java
case "R": statusLabel = "검수중"; underReview = true;
if (underReview) { ... result.setCode("E104");
```

### CX-12 종료('C') 과제라는 이유만으로는 재배포를 막지 않고, 마감 판정에 맡긴다
- 근거: `S:190-192`, `S:222-225`, `S:326-328`
```java
if (closedByTeacher && !extended) {
    notes.add("교사 종료 과제 → 마감 후 유예 기간 판정으로 진행");
```
- 'C' 과제도 CX-16~19 를 그대로 적용받아 마감 후 72시간 미만이면 허용된다(`S:327` "종료 과제이지만 유예 기간 내 → 허용").

### CX-13 과제 상태 코드는 trim + 대문자로 정규화하며, 빈 값 · 알 수 없는 값은 진행중으로 간주해 막지 않는다
- 근거: `S:172-176`, `S:205-212`
```java
st = st.trim().toUpperCase();
warnings.add("과제 상태 코드가 비어 있습니다. 진행중으로 간주합니다.");
warnings.add("알 수 없는 과제 상태 코드: " + st + " (진행중으로 간주)");
```
- 그래서 재배포에서는 소문자 `x` 도 연장으로 취급되지만, 신규 배포(CX-03, CX-06)는 정확 일치 `"D"` · `"X"` 만 인식한다.

### CX-14 마감 시각이 없는 과제는 마감 규칙을 적용하지 않고 재배포를 허용한다
- 근거: `S:269-273`, `S:310-311`
```java
dueBucket = -1;
warnings.add("과제에 마감 시각이 없습니다. 마감 규칙을 적용하지 않습니다.");
if (dueBucket == -1) { allowed = true;
```

### CX-15 마감 대비 현재 시각은 7개 구간(0~6)으로 분류한다
- 근거: `S:275-302`
```java
if (minutesAfterDue < -72 * 60) { dueBucket = 0; ...
} else if (minutesAfterDue < -24 * 60) { dueBucket = 1; ...
} else if (minutesAfterDue < 0) { dueBucket = 2; ...
```
- 구간: 0 = 마감 72시간 초과 전 · 1 = 24~72시간 전 · 2 = 24시간 이내 전 또는 정각(`S:287-289`) · 3 = 마감 후 72시간 미만(`S:290`) · 4 = 72시간 이상 7일 미만(`S:293`) · 5 = 7일 이상 30일 미만(`S:296`) · 6 = 30일 이상.
- 경과 시간은 `Duration.toHours()` (내림)로 잰다. 정확히 72시간 경과는 구간 4 다.

### CX-16 마감 전, 마감 당일, 마감 정각(구간 ≤ 2)에는 재배포를 허용한다
- 근거: `S:312-313`
```java
} else if (dueBucket <= 2) {
    allowed = true;
```

### CX-17 상태가 연장('X')이면 마감이 얼마나 지났든 재배포를 허용한다
- 근거: `S:314-316`
```java
} else if (extended) {
    allowed = true;
    notes.add("상태 '연장' → 마감 경과에도 재배포 허용");
```

### CX-18 연장이 아닌 과제는 마감 후 72시간(3일) 이상 지나면 재배포할 수 없다
- 근거: `S:317-325`
```java
if (hoursAfterDue >= 72) {
    allowed = false;
    denyReason = "마감 후 유예 기간이 지났습니다. (" + dueText + ") 과제 상태를 '연장'으로 바꾼 뒤 다시 시도하세요.";
```
- 마감 후 72시간 미만(구간 3)은 `else { allowed = true; }` 로 허용된다. 주석의 "7일"과 코드가 다르다(§4).

### CX-19 이미 재배포된 배포(redistributed = 1)는 과제가 연장('X') 상태이거나 사유가 "시스템 오류"일 때만 다시 재배포할 수 있다
- 근거: `S:329-338`
```java
if (allowed && dist.getRedistributed() == 1) {
    if (extended) { ... } else if (reasonCategory == 1) { ... } else {
        allowed = false;
```
- 위 CX-16~18 을 통과한 뒤에 추가로 적용되는 조건이다. 거부 메시지는 "두 번째 재배포는 '연장' 상태이거나 사유가 시스템 오류일 때만 가능합니다."
- 재배포 횟수는 저장되지 않고 0/1 플래그뿐이라 세 번째 이후도 같은 조건이다(`DD:66` `SET redistributed = 1`).

### CX-20 거부 코드는 최초 재배포 거부가 E105, 이미 재배포된 배포의 거부가 E106 이다
- 근거: `S:339-344`
```java
result.setCode(dist.getRedistributed() == 1 ? "E106" : "E105");
```
- 마감 유예 초과(CX-18)로 거부돼도 이미 재배포된 배포면 E106 이 된다.

### CX-21 재배포 사유는 키워드로 분류하며, 앞의 분류가 우선한다. "시스템 오류"(1) 분류가 CX-19 · CX-26 을 좌우한다
- 근거: `S:119-130`
```java
if (rsn.contains("오류") || rsn.contains("장애")) { reasonCategory = 1;
} else if (rsn.contains("연장") || rsn.contains("추가")) { reasonCategory = 2;
} else if (rsn.contains("정정") || rsn.contains("수정")) { reasonCategory = 3;
```
- 이어서 `재시험` · `재평가` → 4, 정규화 후 빈 사유(`"(사유 없음)"`) → 9, 나머지 0(기타)(`S:126-129`).
- 사유 분류 자체는 차단 조건이 아니다. 분류가 결과에 영향을 주는 곳은 CX-19(1)와 CX-26(4)뿐이다.

### CX-22 사유는 공백 정리 후 비면 "(사유 없음)" 으로 대체하고, 200자를 넘으면 197자 + "..." 로 자른다. 사유가 없어도 재배포는 막히지 않는다
- 근거: `S:99-114`
```java
if (rsn.length() == 0) { rsn = "(사유 없음)";
} else if (rsn.length() > 200) { rsn = rsn.substring(0, 197) + "...";
```
- 분류는 자른 뒤 문자열로 하므로 잘려 나간 뒷부분의 키워드는 분류에 반영되지 않는다.

### CX-23 작업자가 담당 교사가 아니어도 재배포는 막지 않고 안내만 남긴다
- 근거: `S:259-261`, `S:94-96`
```java
if (!op.equals("SYSTEM") && teacherId.length() > 0 && !op.equals(teacherId) && !op.startsWith("admin-")) {
    notes.add("담당 교사(" + teacherId + ")가 아닌 작업자(" + op + ")의 재배포");
```
- 작업자가 비면 `SYSTEM`, 20자 초과면 20자로 자른다(`S:87-93`). ID 형식이 `teacher-` · `admin-` · `SYSTEM` 이 아니어도 경고만 한다.

### CX-24 배포 대상 학급이 삭제되어 있어도 재배포는 진행한다 (알림만 없음)
- 근거: `S:242-245`
```java
if (cls == null) {
    className = "(삭제된 학급 #" + dist.getClassId() + ")";
    warnings.add("배포 대상 학급이 존재하지 않습니다. 재배포는 진행하지만 알림은 발송되지 않습니다.");
```
- 알림을 발송하는 코드는 이 범위 파일 어디에도 없다. 문구뿐이다.

### CX-25 재배포가 성공하면 배포의 `redistributed` 만 1 로 바꾸고, 갱신 건수가 0 이면 E107 로 실패시킨다
- 근거: `S:620-626`, `DD:65-67`
```java
int updated = distributionDao.markRedistributed(distributionId);
if (updated == 0) { ... result.setCode("E107");
```
- 배포 시각(`distributed_at`)은 재배포로 바뀌지 않는다. 이 범위의 UPDATE 는 이것 하나뿐이다.

## 3. 제출 상태 판정과 전환

### CX-26 제출 건은 제출 시각과 점수 유무로 4가지로 분류한다
- 근거: `S:407-422`
```java
if (at == null && sc == null) { notSubmitted++; state = "미제출";
} else if (at == null && sc != null) { graded++; state = "채점완료(제출시각없음)";
} else if (at != null && sc == null) { submittedNoScore++; state = "채점전";
} else { graded++; state = "채점완료";
```
- 제출 시각 없이 점수만 있으면 채점 완료로 센다(주석 "이상 데이터").

### CX-27 제출 시각이 마감 시각보다 뒤(엄격히 이후)이면 지연 제출이다
- 근거: `S:428-434`
```java
if (due != null && at.isAfter(due)) {
    late++;
    state = state + "/지연";
```
- 마감이 없으면 지연 판정을 하지 않는다. 지연 제출은 재제출 대상 계산에 영향을 주지 않고 안내(`S:500-502`)에만 쓰인다.

### CX-28 재제출 대상은 기본 "미제출자"이고, 연장 상태면 "채점전 제출자"까지, 재평가 사유(분류 4)면 전원이다
- 근거: `S:485-496`
```java
int reopen = notSubmitted;
if (extended) { reopen = notSubmitted + submittedNoScore; }
if (reasonCategory == 4) { reopen = total;
```
- 분류 4 가 마지막에 덮어쓰므로 연장 여부와 무관하게 전원이 된다. 제출 데이터가 0건이면 `reopen` 은 0 이며 "학급 전원이 재제출 대상" 은 안내 문구일 뿐이다(`S:509-511`).

### CX-29 재배포는 기존 제출을 지우거나 바꾸지 않는다. 미제출을 뺀 건수가 "유지" 건수다
- 근거: `S:483-486`, `S:512-513`
```java
// 재배포는 기존 제출을 지우지 않는다. 미제출자만 '재제출 대상' 으로 계산해 화면에 알려 준다.
int kept = total - notSubmitted;
```
- `submission` 테이블에 쓰는 코드는 없다(읽기 `DD:69-73` 뿐). 재제출 대상은 계산해서 결과 메시지로만 알리고 저장하지 않는다.
- 상태 전환 요약(`S:590-617`): 미제출 → 재제출 대상 / 채점전 → 연장이면 재제출 가능, 아니면 유지 / 채점완료 → 재평가 사유면 재제출 가능(점수 유지), 아니면 유지. 요약도 문구로만 남는다.

## 4. 조회 · 화면 규칙과 주석-코드 차이

### CX-30 배포 목록의 "제출 수"는 제출 시각이 있는 건만 센다. 삭제 과제의 배포는 목록에서 빠진다
- 근거: `DD:26`, `DD:33`
```java
"(SELECT COUNT(*) FROM submission s WHERE s.distribution_id = d.id AND s.submitted_at IS NOT NULL) AS submitted_cnt "
" WHERE a.status <> 'D' "
```
- 재배포 계산(CX-26, 점수만 있는 건도 제출로 침)과 기준이 달라 건수가 다를 수 있고, 코드가 그 차이를 안내한다(`S:636-638`).

### CX-31 재배포 버튼은 재배포 이력이 없거나 과제가 연장('X') 상태인 배포에만 보인다
- 근거: `T-dist:32`
```html
th:if="${d.redistributed == 0 or d.assignmentStatus == 'X'}"
```
- 화면 조건이 서비스 조건(CX-19)보다 좁다. "시스템 오류" 사유로 두 번째 재배포하는 경로는 버튼이 없어 화면에서는 나오지 않고, 직접 POST 로만 가능하다. 화면 폼은 `operator` 를 `teacher-01` 로 고정해 보낸다(`T-dist:33`).

### 주석과 코드가 다른 곳 (코드를 기준으로 위 규칙을 적었다)
| 위치 | 주석 | 코드 동작 | 반영 규칙 |
|---|---|---|---|
| `S:318` | "재배포는 마감 후 7일 이내에만 허용한다 (교무 지침 §4.2)" | `hoursAfterDue >= 72` 면 거부(`S:319`), 즉 3일 | CX-18 |
| `S:223` | "마감 후 3일 안이면" | 위 코드와 일치 | CX-12 |
| `AC:8` | "운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)" | 기본값이 고정 시각(2026-09-15) | CX-08 |
| `S:293-295` | 구간 4 문구 "유예 종료" | 72시간~7일 구간이며 연장 아니면 거부, 7일이라는 경계는 판정에 쓰이지 않음 | CX-15, CX-18 |
| `T-form:13` | "마감 지난 과제는 선택 불가 (연장 상태 제외)" | 화면 · 컨트롤러 모두 마감 정각은 통과(`isBefore`) | CX-06, CX-07 |
