# item-bank — 문항 검색 화면 비즈니스 규칙 후보

> **코드가 하는 일을 적은 후보이며 의도된 정책인지는 확인하지 않았다.**

- 분석 범위: `IB/search.php` 와 그 화면이 부르는 `IB/inc/db.php` · `IB/inc/layout.php` · `IB/vendor/simplelog/Log.php`, 뷰 `v_item_public`
- 범위 밖: `IB/register.php`, `IB/units.php` (같은 규칙이 거기에도 있는지는 확인하지 않았다)
- 경로 약어: `IB/` = `legacy/item-bank-php/`, `SCH` = `db/mariadb/init/01-schema.sql`, `SEED` = `db/mariadb/init/02-seed.sql`
- 런타임: PHP 7.4(`IB/Dockerfile:1`), MariaDB 콜레이션 `utf8mb4_unicode_ci`(`SCH:5`)

## 목차

| ID | 요약 | 확신도 |
|---|---|---|
| BR-01 | 공개(`status='A'`) 문항만 검색된다 | 확실 |
| BR-02 | 난이도를 비우면 난이도 5 문항이 빠진다 (`level < 5`) | 확실 |
| BR-03 | 난이도 `1`~`5` 한 글자면 그 난이도와 같은 문항만 | 확실 |
| BR-04 | 그 밖의 난이도 값은 정수로 바꿔 같은 값 비교 + 경고 | 높음 |
| BR-05 | 키워드는 trim 뒤 100자로 자른다 | 확실 |
| BR-06 | 키워드는 제목 또는 지문 부분 일치, `%` `_` 는 와일드카드 | 확실 |
| BR-07 | 단원 코드는 정확히 일치, 형식이 틀려도 조회는 한다 | 확실 |
| BR-08 | 소문자 단원 코드도 대문자와 같은 결과, 단 폼 선택은 풀린다 | 높음 |
| BR-09 | 등록되지 않은 단원 코드는 경고만 하고 조회한다 | 확실 |
| BR-10 | 태그는 이름 정확히 일치, 50자로 자른다 | 확실 |
| BR-11 | 등록 태그 여부는 PHP 에서 대소문자 구분 비교 | 추정 |
| BR-12 | 정렬 기준 화이트리스트, 기본 정렬은 난이도 내림차순 · ID 오름차순 | 확실 |
| BR-13 | 정렬 방향 기본값은 난이도 · 등록일은 내림차순, 나머지는 오름차순 | 확실 |
| BR-14 | 열 머리글 정렬 링크의 다음 방향 | 확실 |
| BR-15 | 한 페이지 20건, 페이지 번호 1~999 | 확실 |
| BR-16 | 0건 판단은 전체 건수 기준 | 확실 |
| BR-17 | 페이지 링크는 2페이지 이상일 때 모든 번호를 표시 | 확실 |
| BR-18 | 파라미터가 배열이면 첫 값만 쓴다 | 확실 |
| BR-19 | 난이도 표시 라벨 | 확실 |
| BR-20 | DB 연결 실패 시 오류 상세를 화면에 보여 준다 | 확실 |
| BR-21 | 검색 중 오류는 일반 메시지로 바꾸고 로그를 남긴다 | 확실 |

---

## 1. 노출 대상

**BR-01**
- 규칙: 검색 결과와 건수는 뷰 `v_item_public` 에서만 읽으므로 `item.status = 'A'` 인 문항만 나온다. `D`(삭제) · `R`(검수중)은 어떤 조건으로도 나오지 않는다.
- 근거: `SCH:68-70`, 조회 위치 `IB/search.php:521-526`
- 근거 코드:
  ```sql
  FROM item i
  JOIN unit u ON u.id = i.unit_id
  WHERE i.status = 'A';
  ```
  ```php
      $select = "SELECT id, title, unit_code, level, tag_names, created_at";
      $from   = " FROM v_item_public";
      $limit  = " LIMIT 20 OFFSET " . (int)$offset;

      $sql      = $select . $from . $where . $orderBy . $limit;
      $countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
  ```
- 확신도: 확실
- 비고: 상태 코드 의미는 스키마 주석 `-- A=공개 D=삭제 R=검수중`(`SCH:26`). 삭제 플래그가 아니라 상태 코드로 판정한다(`SCH:50`). INNER JOIN `unit` 은 `item.unit_id NOT NULL` + FK(`SCH:22`, `:32`) 때문에 추가로 걸러내는 행이 없다.

## 2. 난이도 (`level`)

**BR-02**
- 규칙: `level` 이 비어 있으면(trim 후 `''`) `level < 5` 조건이 붙어 **난이도 5 문항이 결과에서 빠진다.** 난이도 5 문항은 `level=5` 를 명시해야만 나온다.
- 근거: `IB/search.php:211-216`
- 근거 코드:
  ```php
      $level = trim($level);

      // 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
      if ($level == '') {
          $where .= " AND level < 5";
      }
  ```
- 확신도: 확실
- 비고:
  - **주석-코드 불일치.** 주석은 "1~5 모두 포함", 코드는 1~4만 포함한다. 결과가 실제로 달라진다.
  - 도달 가능: 파라미터 없이 처음 들어올 때, 폼에서 난이도 "전체"(`value=""`, `IB/search.php:192`)를 고를 때 모두 이 분기다. 즉 **기본 화면의 기본 조건**이다.
  - 시드 기준 공개 문항 26건 중 난이도 5 공개 문항 6건(24~29번)이 기본 검색에서 빠지고 20건만 나온다(`SEED` 주석 "status='A' AND level<5 는 정확히 20건").
  - 조건 요약(`$summary`)에 아무것도 추가하지 않으므로 화면에는 "조건 없이 검색했습니다."(`IB/search.php:356`)로 보인다.
  - `sort=level&dir=asc|desc` 로 정렬해도 난이도 조건이 비어 있으면 5는 나오지 않는다.

**BR-03**
- 규칙: `level` 이 trim 후 정확히 `1`~`5` 한 글자면 `level = ?`(정수) 조건을 붙인다.
- 근거: `IB/search.php:217-222`
- 근거 코드:
  ```php
      else if (preg_match('/^[1-5]$/', $level)) {
          $where .= " AND level = ?";
          $types .= 'i';
          $values[] = (int)$level;
          $summary[] = '난이도 ' . $level;
      }
  ```
- 확신도: 확실
- 비고: 앞의 BR-02(빈값) 분기가 아닐 때만 적용된다.

**BR-04**
- 규칙: `level` 이 비어 있지 않고 `1`~`5` 한 글자도 아니면 경고 "난이도는 1~5 사이여야 합니다."를 띄우고, PHP `(int)` 로 바꾼 값과 같은 문항을 조회한다. 따라서 `0`·`6`·`abc`(→0)는 0건이지만, `3abc`·`3.0`·`03` 처럼 앞자리가 1~5로 바뀌는 값은 **경고와 함께 그 난이도의 결과가 나온다.**
- 근거: `IB/search.php:223-230`
- 근거 코드:
  ```php
      else {
          // 1~5 밖의 값은 그대로 정수로 비교한다 (대개 0건)
          $warnings[] = '난이도는 1~5 사이여야 합니다.';
          $where .= " AND level = ?";
          $types .= 'i';
          $values[] = (int)$level;
          $summary[] = '난이도 ' . (int)$level;
      }
  ```
- 확신도: 높음 (PHP 7.4 의 문자열 → 정수 변환 규칙에 따른 해석이다. 실행해서 확인하지는 않았다)
- 비고: 주석 "대개 0건"은 `3abc` 같은 입력에서 맞지 않는다. 경고 문구와 실제 결과가 어긋나는 경우가 있다(불일치 표 참고). 요청을 거절하지 않는다.

**BR-19**
- 규칙: 난이도 선택 목록은 `전체`(빈값)와 1~5 다섯 단계이며 라벨은 1 매우 쉬움 · 2 쉬움 · 3 보통 · 4 어려움 · 5 최상이다.
- 근거: `IB/search.php:191-206`
- 근거 코드:
  ```php
      $levelOptions = array();
      $levelOptions[] = array('value' => '', 'label' => '전체', 'selected' => (trim($level) === ''));
      $levelLabels = array(
          1 => '1 (매우 쉬움)',
          2 => '2 (쉬움)',
          3 => '3 (보통)',
          4 => '4 (어려움)',
          5 => '5 (최상)',
      );
      foreach ($levelLabels as $lv => $label) {
          $levelOptions[] = array(
              'value'    => (string)$lv,
              'label'    => $label,
              'selected' => ((string)$lv === trim($level)),
          );
      }
  ```
- 확신도: 확실
- 비고: 라벨이 "전체"지만 선택하면 BR-02 로 난이도 5가 빠진다. DB 에는 1~5 CHECK 제약이 없고 주석(`SCH:25`)뿐이다.

## 3. 키워드 (`q`)

**BR-05**
- 규칙: 키워드는 앞뒤 공백을 제거한 뒤 100자(UTF-8 문자 수)를 넘으면 100자로 자르고 경고한다. 공백만 입력하면 키워드 조건이 없다.
- 근거: `IB/search.php:85-90`, 폼 `IB/search.php:494`
- 근거 코드:
  ```php
      $q = trim($q);
      if (mb_strlen($q, 'UTF-8') > 100) {
          $q = mb_substr($q, 0, 100, 'UTF-8');
          $warnings[] = '키워드가 너무 길어 100자까지만 사용했습니다.';
      }
      if ($q !== '') {
  ```
  ```php
      $html = '<input type="text" id="q" name="q" size="30" maxlength="100" value="' . h($q) . '">';
  ```
- 확신도: 확실
- 비고: 같은 100자 제한이 서버(자르기)와 폼(`maxlength="100"`) 두 곳에 있다. 자른 뒤의 값으로 검색 · 조건 요약 · 링크를 만든다(`IB/search.php:97`, `:102`, `:373`).

**BR-06**
- 규칙: 키워드가 있으면 `title` 또는 `stem` 에 키워드가 들어 있는 문항을 찾는다(`LIKE '%키워드%'`). 키워드 안의 `%` · `_` 는 이스케이프하지 않아 와일드카드로 동작하고, 이때와 한 글자 키워드일 때 경고만 띄운다.
- 근거: `IB/search.php:90-104`
- 근거 코드:
  ```php
      if ($q !== '') {
          if (mb_strlen($q, 'UTF-8') === 1) {
              $warnings[] = '키워드가 한 글자라 결과가 많을 수 있습니다.';
          }
          if (strpos($q, '%') !== false || strpos($q, '_') !== false) {
              $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';
          }
          $like = '%' . $q . '%';
          $where .= " AND (title LIKE ? OR stem LIKE ?)";
          $types .= 'ss';
          $values[] = $like;
          $values[] = $like;
          $summary[] = '키워드 "' . $q . '"';
          Log::debug('search keyword', array('q' => $q));
      }
  ```
- 확신도: 확실
- 비고: 한 글자 판정은 BR-05 에서 trim · 자르기를 마친 값으로 한다. 콜레이션이 `utf8mb4_unicode_ci` 라 영문 대소문자를 구분하지 않는다. `stem` 은 화면에 표시하지 않으므로 지문에만 일치한 문항은 결과 표에서 일치 이유가 보이지 않는다. `Log::debug` 는 임계값 INFO(`IB/search.php:27`) 때문에 기록되지 않는다.

## 4. 단원 (`unit`)

**BR-07**
- 규칙: 단원 코드는 trim 후 `unit_code = ?` 로 정확히 일치하는 문항만 찾는다. 형식이 `영문1자 + 숫자1~2자리 + '-' + 숫자1~2자리` 가 아니거나 대문자가 아니면 경고만 하고 **입력값 그대로 조회한다.**
- 근거: `IB/search.php:109-121`
- 근거 코드:
  ```php
      $unit = trim($unit);
      if ($unit !== '') {
          if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
              // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
              $warnings[] = '단원 코드 형식이 올바르지 않습니다. (예: M5-1)';
          }
          if ($unit !== strtoupper($unit)) {
              $warnings[] = '단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)';
          }
          $where .= " AND unit_code = ?";
          $types .= 's';
          $values[] = $unit;
  ```
- 확신도: 확실
- 비고: 형식 정규식은 소문자 첫 글자도 허용한다(`[A-Za-z]`). 대문자 여부는 별도 경고로 검사한다.

**BR-08**
- 규칙: 소문자 단원 코드(예: `m5-1`)는 "대문자로 입력하세요" 경고가 뜨지만, DB 비교가 대소문자를 구분하지 않아 `M5-1` 과 **같은 결과가 나오고** 단원 이름도 찾아진다. 반면 단원 select 의 선택 표시는 PHP `===` 로 비교해 **어떤 단원도 선택되지 않은 상태**로 그려진다.
- 근거: DB 쪽 `IB/search.php:118`, `:125`, 콜레이션 `SCH:18`; PHP 쪽 `IB/search.php:162`, `:451`
- 근거 코드:
  ```php
          $where .= " AND unit_code = ?";
  ```
  ```php
              $stmt = $conn->prepare("SELECT name, grade FROM unit WHERE code = ?");
  ```
  ```sql
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  ```
  ```php
                      'selected' => ($r['code'] === $unit),
  ```
  ```php
      $html .= '  <option value=""' . ($unit === '' ? ' selected' : '') . '>전체 단원</option>' . "\n";
  ```
- 확신도: 높음 (콜레이션 규칙에 따른 해석이며 실행해서 확인하지는 않았다)
- 비고: 선택된 option 이 없으면 브라우저는 첫 option("전체 단원")을 보여 준다. 사용자가 그 상태로 폼을 다시 제출하면 단원 조건이 사라진다(추정). 경고 문구 "(입력값 그대로 조회합니다)"는 결과가 달라질 것처럼 읽히지만 결과는 대문자와 같다. 불일치 표 참고.

**BR-09**
- 규칙: 입력한 단원 코드가 `unit` 테이블에 없으면 "등록되지 않은 단원 코드입니다" 경고를 띄우고 요약에 코드만 적는다. 조회는 그대로 해서 대개 0건이 된다. 있으면 요약에 `단원명 (N학년)` 을 붙인다.
- 근거: `IB/search.php:141-146`
- 근거 코드:
  ```php
          if ($unitName === '') {
              $warnings[] = '등록되지 않은 단원 코드입니다: ' . $unit;
              $summary[] = '단원 ' . $unit;
          } else {
              $summary[] = '단원 ' . $unit . ' ' . $unitName;
          }
  ```
- 확신도: 확실
- 비고: 단원 조회가 준비 · 실행에 실패해도(`IB/search.php:126`, `:128` 의 `false` 분기) `$unitName` 이 빈 값으로 남아 같은 "등록되지 않은" 경고가 나온다. BR-07 의 `if ($unit !== '')` 블록 안에서만 적용된다.

## 5. 태그 (`tag`)

**BR-10**
- 규칙: 태그는 trim 후 50자(UTF-8 문자 수)를 넘으면 50자로 자르고, 그 이름과 **정확히 같은** 태그가 붙은 문항만 찾는다(`EXISTS … t.name = ?`). `%` · `_` 가 있으면 "부분 일치를 지원하지 않습니다" 경고만 띄우고 문자 그대로 비교한다.
- 근거: `IB/search.php:235-248`
- 근거 코드:
  ```php
      $tag = trim($tag);
      if ($tag !== '') {
          if (strpos($tag, '%') !== false || strpos($tag, '_') !== false) {
              $warnings[] = '태그는 부분 일치를 지원하지 않습니다.';
          }
          if (mb_strlen($tag, 'UTF-8') > 50) {
              $tag = mb_substr($tag, 0, 50, 'UTF-8');
              $warnings[] = '태그 이름이 너무 길어 50자까지만 사용했습니다.';
          }
          $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
                  . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
          $types .= 's';
          $values[] = $tag;
          $summary[] = '태그 ' . $tag;
  ```
- 확신도: 확실
- 비고: 가공 순서 — `%`/`_` 검사는 자르기 **전** 값으로, SQL 바인딩 · 요약 · 등록 여부 확인(BR-11)은 자른 **뒤** 값으로 한다. 50자는 `tag.name VARCHAR(50)`(`SCH:37`)과 같다. 태그는 한 개만 지정할 수 있다. 콜레이션 때문에 영문 대소문자는 구분하지 않는다.

**BR-11**
- 규칙: 입력 태그가 태그 목록(`SELECT name FROM tag`)에 있는지 PHP `===`(대소문자 구분)로 비교해 없으면 "등록되지 않은 태그입니다" 경고를 띄운다. 조회는 그대로 한다.
- 근거: `IB/search.php:250-260`
- 근거 코드:
  ```php
          // 등록된 태그인지 확인 (없어도 조회는 그대로 한다 → 0건)
          $tagKnown = false;
          foreach ($tagOptions as $opt) {
              if ($opt['value'] !== '' && $opt['value'] === $tag) {
                  $tagKnown = true;
                  break;
              }
          }
          if (!$tagKnown) {
              $warnings[] = '등록되지 않은 태그입니다: ' . $tag;
          }
  ```
- 확신도: 추정 (영문 태그가 있을 때의 동작은 콜레이션에서 추론했다)
- 비고: SQL(`t.name = ?`)은 대소문자를 구분하지 않아서 영문 태그를 대소문자만 다르게 입력하면 "등록되지 않은 태그" 경고와 **검색 결과가 함께** 나올 수 있다. 주석 "→ 0건"과 어긋난다. 시드 태그 6개(`SEED` 태그 INSERT)는 모두 한글이라 **현재 데이터에서는 차이가 나지 않는다.** BR-10 의 `if ($tag !== '')` 블록 안에서만 적용된다.

## 6. 정렬 (`sort`, `dir`)

**BR-12**
- 규칙: 정렬 기준은 `id` · `title` · `unit` · `level` · `created` 다섯 개(소문자로 바꾼 뒤 비교)이고, 비어 있으면 `level DESC, id ASC` 로 정렬한다. 목록에 없는 값이면 경고를 띄우고 같은 기본 정렬을 쓴다. `id` 외에는 모두 `id ASC` 가 2차 키이고, `unit` 정렬은 단원 안에서 `level DESC` 를 먼저 적용한다.
- 근거: `IB/search.php:266`, `:275-327`
- 근거 코드:
  ```php
      $sort = strtolower(trim($sort));
  ```
  ```php
      $orderBy = '';
      switch ($sort) {
          case 'id':
              if ($dir === 'desc') {
                  $orderBy = " ORDER BY id DESC";
              } else {
                  $orderBy = " ORDER BY id ASC";
              }
              break;

          case 'title':
              if ($dir === 'desc') {
                  $orderBy = " ORDER BY title DESC, id ASC";
              } else {
                  $orderBy = " ORDER BY title ASC, id ASC";
              }
              break;

          case 'unit':
              if ($dir === 'desc') {
                  $orderBy = " ORDER BY unit_code DESC, level DESC, id ASC";
              } else {
                  $orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
              }
              break;

          case 'level':
              if ($dir === 'asc') {
                  $orderBy = " ORDER BY level ASC, id ASC";
              } else {
                  $orderBy = " ORDER BY level DESC, id ASC";
              }
              break;

          case 'created':
              if ($dir === 'asc') {
                  $orderBy = " ORDER BY created_at ASC, id ASC";
              } else {
                  $orderBy = " ORDER BY created_at DESC, id ASC";
              }
              break;

          case '':
              // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
              $orderBy = " ORDER BY level DESC, id ASC";
              break;

          default:
              $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
              $sort = '';
              $orderBy = " ORDER BY level DESC, id ASC";
              break;
      }
  ```
- 확신도: 확실
- 비고: SQL 에는 화이트리스트로 고른 고정 문자열만 들어간다. `created` 는 열 머리글 링크가 없어(BR-14) URL 을 직접 입력할 때만 쓰인다. 알 수 없는 `sort` 에 `dir` 이 함께 오면 `sort` 만 비워지고 `dir` 은 hidden 필드로 남는다(`IB/search.php:501-503`). 결과에는 영향이 없다.

**BR-13**
- 규칙: `dir` 은 소문자로 바꾼 뒤 `asc`·`desc` 만 받고, 그 밖의 값은 경고 후 비운다. 비어 있으면 `level` · `created` 는 내림차순, `id` · `title` · `unit` 은 오름차순으로 정렬하고 화면 표시도 같은 방향으로 맞춘다.
- 근거: `IB/search.php:267-273`, `:328-331`, SQL 방향은 BR-12 의 각 `case`
- 근거 코드:
  ```php
      $dir  = strtolower(trim($dir));
      if ($dir !== 'asc' && $dir !== 'desc') {
          if ($dir !== '') {
              $warnings[] = '정렬 방향은 asc 또는 desc 만 가능합니다.';
          }
          $dir = '';
      }
  ```
  ```php
      if ($sort !== '' && $dir === '') {
          // 화면 표시용 기본 방향
          $dir = ($sort === 'level' || $sort === 'created') ? 'desc' : 'asc';
      }
  ```
- 확신도: 확실
- 비고: SQL 쪽 기본 방향(`id`·`title`·`unit` 은 `else` 가 ASC, `level`·`created` 는 `else` 가 DESC)과 표시용 기본 방향(`:330`)이 두 곳에 따로 구현되어 있지만 현재는 결과가 같다.

**BR-14**
- 규칙: 열 머리글(ID · 제목 · 단원 · 난이도) 정렬 링크는 현재 정렬 중인 열이면 방향을 뒤집고(▲ asc → desc, ▼ desc → asc), 아니면 `asc` 로 시작한다. 단, 난이도 열은 처음 누를 때 `desc` 다. 태그 열은 정렬 링크가 없다.
- 근거: `IB/search.php:392-418`
- 근거 코드:
  ```php
      $sortColumns = array(
          'id'    => 'ID',
          'title' => '제목',
          'unit'  => '단원',
          'level' => '난이도',
      );
      $sortLinks = array();
      foreach ($sortColumns as $key => $label) {
          $nextDir = 'asc';
          $mark = '';
          if ($sort === $key) {
              if ($dir === 'asc') {
                  $nextDir = 'desc';
                  $mark = ' ▲';
              } else {
                  $nextDir = 'asc';
                  $mark = ' ▼';
              }
          } else if ($key === 'level') {
              // 난이도는 첫 클릭에 내림차순
              $nextDir = 'desc';
          }
          $href = 'search.php' . ($baseQs === '' ? '?' : $baseQs . '&')
                . 'sort=' . rawurlencode($key) . '&dir=' . $nextDir;
          $sortLinks[$key] = '<a href="' . h($href) . '">' . h($label) . h($mark) . '</a>';
      }
      $sortLinks['tags'] = '태그';
  ```
- 확신도: 확실
- 비고: 정렬 링크는 검색 조건(`q`·`unit`·`level`·`tag`)은 유지하고 `page` 는 빼므로 정렬을 바꾸면 1페이지로 돌아간다(`IB/search.php:369-387`). 기본 정렬(`sort=''`) 상태에서는 결과가 난이도 내림차순이어도 난이도 열에 ▼ 표시가 없다. 결과 표는 1건 이상일 때만 그려지므로(BR-16) 0건이면 이 링크도 없다.

## 7. 페이지 (`page`)

**BR-15**
- 규칙: 한 페이지는 20건(`LIMIT 20 OFFSET (page-1)*20`)이다. `page` 는 숫자로만 된 값만 받고, 그 밖의 값은 경고 후 1페이지로 한다. 0 이하는 경고 없이 1로, 999 초과는 경고 후 999로 바꾼다.
- 근거: `IB/search.php:336-349`, `:523`, `:553`
- 근거 코드:
  ```php
      $page = 1;
      if (preg_match('/^[0-9]+$/', $pageRaw)) {
          $page = (int)$pageRaw;
      } else if ($pageRaw !== '' && $pageRaw !== '1') {
          $warnings[] = '페이지 번호가 올바르지 않아 1페이지를 표시합니다.';
      }
      if ($page < 1) {
          $page = 1;
      }
      if ($page > 999) {
          $page = 999;
          $warnings[] = '페이지 번호는 999 를 넘을 수 없습니다.';
      }
      $offset = ($page - 1) * 20;
  ```
  ```php
      $limit  = " LIMIT 20 OFFSET " . (int)$offset;
  ```
  ```php
          'page_size'    => 20,
  ```
- 확신도: 확실
- 비고: 페이지 크기 20이 상수 없이 세 곳(`:349`, `:523`, `:553`)에 리터럴로 있다. `-1` 은 정규식에 맞지 않아 경고가 나오고, `0` 은 정규식에 맞아 경고 없이 1이 된다. 전체 페이지 수보다 큰 페이지(예: 결과 20건에서 `page=2`)를 요청하면 건수는 1건 이상이라 표 머리글은 그려지지만 행은 비어 있다(BR-16).

**BR-16**
- 규칙: "검색 결과가 없습니다" 메시지와 표 생략은 현재 페이지 행 수가 아니라 **전체 건수(`COUNT(*)`)가 0일 때만** 적용된다.
- 근거: `IB/search.php:655-660`
- 근거 코드:
  ```php
      echo '<p id="count">검색 결과 ' . (int)$total . '건</p>' . "\n";

      if ($total === 0) {
          echo '<p id="message">검색 결과가 없습니다</p>' . "\n";
          return;
      }
  ```
- 확신도: 확실
- 비고: 전체 건수는 페이지와 무관한 같은 WHERE 로 센다(`IB/search.php:526`).

**BR-17**
- 규칙: 전체 페이지 수 = `ceil(전체 건수 / 20)` 이고, 2 이상일 때만 페이지 링크를 그린다. 1페이지부터 마지막 페이지까지 번호를 모두 나열하며, 현재 페이지는 굵게, 첫 페이지가 아니면 "이전", 마지막이 아니면 "다음" 링크를 붙인다.
- 근거: `IB/search.php:684-703`
- 근거 코드:
  ```php
      $pageSize = $built['page_size'];
      $pages = (int)ceil($total / $pageSize);
      if ($pages > 1) {
          $cur = $built['page'];
          echo '<p class="pager">' . "\n";
          if ($cur > 1) {
              echo '  <a href="' . h($built['page_prefix'] . ($cur - 1)) . '">이전</a>' . "\n";
          }
          for ($p = 1; $p <= $pages; $p++) {
              if ($p === $cur) {
                  echo '  <strong>' . $p . '</strong>' . "\n";
              } else {
                  echo '  <a href="' . h($built['page_prefix'] . $p) . '">' . $p . '</a>' . "\n";
              }
          }
          if ($cur < $pages) {
              echo '  <a href="' . h($built['page_prefix'] . ($cur + 1)) . '">다음</a>' . "\n";
          }
          echo "</p>\n";
      }
  ```
- 확신도: 확실
- 비고: 페이지 링크는 검색 조건과 `sort`·`dir` 을 유지한다(`IB/search.php:423-430`). 번호 개수 제한이 없어서 결과가 많으면 링크가 모두 나열된다. 링크로는 999 를 넘는 페이지로 갈 수 있지만 BR-15 에 의해 999페이지로 고정된다.

## 8. 입력 공통 · 오류 처리

**BR-18**
- 규칙: 모든 GET 파라미터는 배열로 들어오면(`q[]=a&q[]=b`) 첫 값만 문자열로 쓴다.
- 근거: `IB/search.php:53-80` (7개 파라미터 모두 같은 형태, 예: `:53-56`)
- 근거 코드:
  ```php
      $q = '';
      if (isset($params['q'])) {
          $q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
      }
  ```
- 확신도: 확실
- 비고: `page` 만 기본값이 `'1'`(`IB/search.php:77`)이고 나머지는 `''` 이다.

**BR-20**
- 규칙: DB 연결에 실패하면 `"DB 연결 실패: " + mysqli 연결 오류 문구`를 화면에 그대로(HTML 이스케이프만 해서) 보여 주고, 재시도 안내 문구와 함께 처리를 끝낸다. 로그는 남기지 않는다.
- 근거: `IB/inc/db.php:22-26`, 호출 위치 `IB/search.php:712-719`
- 근거 코드:
  ```php
      if ($conn->connect_errno) {
          $err = $conn->connect_error;
          $conn = null;
          throw new RuntimeException('DB 연결 실패: ' . $err);
      }
  ```
  ```php
  try {
      $conn = db_connect();
  } catch (RuntimeException $e) {
      echo '<p class="error">' . h($e->getMessage()) . "</p>\n";
      echo '<p>DB가 준비되는 중일 수 있습니다. 잠시 후 새로 고침하세요.</p>' . "\n";
      render_footer();
      exit;
  }
  ```
- 확신도: 확실
- 비고: 연결 오류 문구에는 호스트 이름 등이 들어갈 수 있다. BR-21 과 처리 방식이 다르다(여기는 상세 노출 · 로그 없음, BR-21 은 일반 메시지 · 로그 있음).

**BR-21**
- 규칙: 검색 조건 조립이나 쿼리 실행 중 `RuntimeException` 이 나면 사용자에게는 "검색 중 오류가 발생했습니다."만 보여 주고 상세는 `Log::error` 로 남긴다. 바인딩 타입 개수와 값 개수가 다르면 쿼리를 실행하기 전에 이 예외를 던진다.
- 근거: `IB/search.php:721-729`, `:535-539`, 예외 발생 위치 `:578-580`, `:588-590`, `:601-603`, `:611-613`
- 근거 코드:
  ```php
  try {
      $built  = buildSearchQuery($_GET, $conn);
      renderSearchForm($built);
      $result = runSearchQuery($built, $conn);
      renderResultTable($built, $result);
  } catch (RuntimeException $e) {
      Log::error('search failed', array('msg' => $e->getMessage()));
      echo '<p class="error">검색 중 오류가 발생했습니다.</p>' . "\n";
  }
  ```
  ```php
      // 바인딩 개수와 타입 문자열 길이가 다르면 여기서 막는다
      if (strlen($types) !== count($values)) {
          Log::error('bind mismatch', array('types' => $types, 'values' => count($values)));
          throw new RuntimeException('검색 조건 조립 오류');
      }
  ```
- 확신도: 확실
- 비고: 폼 출력(`:723`)이 쿼리 실행(`:724`)보다 먼저라서 실행 단계에서 실패하면 폼은 보이고 결과 자리에만 오류 문구가 나온다. `RuntimeException` 이 아닌 오류(`Error` 등)는 잡지 않는다. 현재 코드에서는 `$types` 와 `$values` 를 항상 함께 추가하므로 바인딩 불일치 분기에 도달하는 경로를 찾지 못했다(방어 코드).

---

## 이 화면에 없는 것

| 항목 | 확인 결과 |
|---|---|
| 로그인 · 권한 · 교사별 문항 제한 | 이 모듈(검색 화면)에 없음. 세션 · 인증 코드가 없다 |
| 문항 등록 · 수정 · 삭제 | 이 화면에 없음 (`register.php` 는 범위 밖) |
| 다중 태그 · 다중 단원 · 난이도 범위 검색 | 없음. 각각 값 하나만 받는다 |
| 학년으로 검색 | 없음. 학년은 단원 select 묶음(`optgroup`) 표시와 요약 문구에만 쓴다(`IB/search.php:133`, `:454-455`) |
| 검색 결과 수 상한 | 없음. 페이지 번호 999 상한(BR-15)만 있다 |

## 불일치 · 차이 목록

| # | 종류 | 내용 | 결과가 실제로 어긋나는가 | 관련 규칙 |
|---|---|---|---|---|
| 1 | 주석 ↔ 코드 | `IB/search.php:213` 주석 "1~5 모두 포함" ↔ `:215` `level < 5` | **예** — 기본 검색에서 난이도 5 문항이 빠진다(시드 기준 6건) | BR-02 |
| 2 | 두 곳 다른 구현 | 단원 코드 비교: DB `=`(대소문자 무시, `SCH:18`) ↔ PHP `===`(대소문자 구분, `IB/search.php:162`, `:451`) | **예** — 소문자 입력 시 결과는 나오지만 select 선택이 풀린다 | BR-08 |
| 3 | 두 곳 다른 구현 | 태그 비교: DB `t.name = ?`(대소문자 무시) ↔ PHP `===`(`IB/search.php:253`), 주석 "→ 0건" | 영문 태그가 있을 때만. 시드 데이터(한글 태그)에서는 아니오 | BR-11 |
| 4 | 메시지 ↔ 동작 | `IB/search.php:225` 경고 "난이도는 1~5 사이여야 합니다." 와 `:224` 주석 "대개 0건" ↔ `(int)` 변환으로 `3abc` 는 난이도 3 결과가 나옴 | 예 — 비정상 입력에서만 | BR-04 |
| 5 | 메시지 ↔ 동작 | `IB/search.php:116` 경고 "(입력값 그대로 조회합니다)" — 문구 자체는 사실이지만 대소문자 무시 비교라 대문자 입력과 결과가 같다 | 아니오 — 참고용 | BR-08 |
| 6 | 두 곳 같은 구현 | 기본 정렬 방향이 SQL(`case` 별 `else`)과 표시(`:330`)에 따로 있다 | 아니오 — 현재는 일치 | BR-13 |
| 7 | 두 곳 같은 구현 | 키워드 100자 제한이 서버(`:86-88`)와 폼(`:494`)에 있다 | 아니오 — 현재는 일치 | BR-05 |

## 이관 회고

| 바뀐 지점 | 레거시 동작 | AI가 만든 동작 | 테스트가 잡았나 | 다음에 막을 방법 |
|---|---|---|---|---|
| 검색 입력값 경고 표시 (BR-04~BR-15) | 잘못되거나 보정된 입력마다 경고 문구를 화면 `#warnings` 목록에 보여 주고 검색은 계속함 | 경고를 계산은 하지만 응답에 넣지 않고 DEBUG 로그에만 남김(운영 INFO 설정에서는 기록도 안 됨) | 못 잡음 (비교 대상이 `status` · `rows` · `count` · `message`뿐) | 이관 전에 규칙마다 사용자에게 보이는 출력(경고 · 요약 · 링크)을 목록으로 적고, 새 응답에서 빠지는 항목은 구현 전에 사람 승인을 받는다 |
