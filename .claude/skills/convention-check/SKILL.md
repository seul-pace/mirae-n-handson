---
name: convention-check
description: 변경된 코드(또는 지정한 경로)가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지키는지 예/아니오로 점검하고 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 데 있나 봐 줘" 같은 요청에 쓴다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일 전체)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기
- 인자(`$ARGUMENTS`)가 있으면 그 경로만 본다. 디렉터리면 Glob 으로 `*.java` · `*.ts` · `*.tsx` 를 모은다.
- 인자가 없으면 저장소 루트에서:
  - `git status --porcelain` — `??` 줄은 add 하지 않은 새 파일, 나머지는 수정 · 추가 파일이다. `D` 는 제외한다.
  - `git diff HEAD --name-only` 로 목록을 보충하고, 수정 파일은 `git diff HEAD -U0 -- <파일>` 로 바뀐 줄 범위를 얻는다.
- 새 파일은 전체를, 수정 파일은 바뀐 줄만 판정한다. 줄번호는 현재 파일 기준으로 Read 해서 확인한다.
- 대상이 없으면 "점검할 변경이 없습니다" 한 줄로 끝낸다.

## 2. 점검 항목 (modern/api = A, modern/web = W)
1. [A] 컨트롤러(`*Controller.java`)는 서비스만 주입받는다. `Repository` 필드 · SQL 문자열 · 다른 도메인 패키지의 `*Repository` import 가 없다.
2. [A] 컨트롤러 핸들러의 반환 타입이 `@Entity` 클래스(또는 그 `List`)가 아니다.
3. [A] `src/main` 에 public setter(`public … set[A-Z]…(`)가 추가되지 않았다.
4. [A] 빈 `catch` 블록이 없고, 컨트롤러 메서드 안에 `try` 로 상태 코드를 만드는 코드가 없다.
5. [A] `System.out/err.println` · `printStackTrace()` 가 없고, `log.xxx(` 메시지를 `+` 로 이어 붙이지 않는다.
6. [A] 와일드카드 import(`import ….*;`) · `Collectors.toList()` · `lombok.` import 가 없다.
7. [W] `src/api/client.ts` 밖에서 `fetch(` 를 직접 부르지 않고, `: any` · `as any` · `<any>` 가 없다.
8. [공통] 변경 목록에 `legacy/` · `db/` · `build.gradle` · `package.json` · `modern/api/src/main/resources/application.yml` · 루트 `.env` 가 없다.

- 해당 모듈 파일이 대상에 없으면 그 항목은 "해당 없음"이다.
- 목록에 없는 규칙(로그 레벨, 상수화, 테스트 유무, 트랜잭션 안 외부 호출 등)은 판정하지 않는다. 눈에 띄면 "확인 필요"에 근거와 함께 적는다.

## 3. 출력 형식 (이 순서로 고정)

### 판정 요약
점검 대상: 파일 N개 (새 파일 a · 수정 b)

| # | 항목 | 판정 |
|---|---|---|
| 1 | 컨트롤러 주입 · SQL | 통과 / 위반 k건 / 해당 없음 |
| … | … | … |

### 위반 목록
| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/src/main/java/.../FooController.java:23` | #1 컨트롤러가 Repository 주입 | FooService 를 거쳐 조회 |

- 위반이 없으면 표 대신 "위반 없음" 한 줄을 쓴다.
- 경로는 저장소 루트 기준, 줄번호는 실제 Read 로 확인한 값만 쓴다.

### 확인 필요
- 근거 라인을 특정하지 못했거나 목록 밖 규칙에 해당하는 의심 사항. 없으면 이 절을 생략한다.
