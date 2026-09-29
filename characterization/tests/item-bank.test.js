// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 케이스 설계 근거: docs/item-bank/BUSINESS-RULES.md (BR-xx 는 그 문서의 규칙 ID)
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 레거시 기본 주소).
//   테스트 코드에 주소를 직접 적지 않는다.
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 이관 후 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기(skipIf)를 두지 않는다.
// - 케이스 이름이 스냅샷 키다. 이름을 바꾸면 스냅샷을 다시 찍어야 한다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

// 긴 입력값 — 글자 수 경계를 정확히 맞추려고 만들어 쓴다(한글이라 바이트 수와 글자 수가 다르다)
const KEYWORD_100 = '분수' + '가'.repeat(98);
const KEYWORD_101 = '분수' + '가'.repeat(99);
const TAG_200 = '계산' + '가'.repeat(198);

function search(params) {
  return fetchNormalized(MODULE, PATH, params);
}

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('단원 M5-1 — 공개 문항만 · 난이도 5 제외 · 기본 정렬 (BR-01 BR-02 BR-07 BR-09 BR-12)', async () => {
      expect(await search({ unit: 'M5-1' })).toMatchSnapshot();
    });

    it('키워드 분수 + 난이도 3 + 제목 오름차순 — 검수중 17번 제외 (BR-01 BR-03 BR-06 BR-12)', async () => {
      expect(await search({ q: '분수', level: '3', sort: 'title', dir: 'asc' })).toMatchSnapshot();
    });

    it('태그 계산 + ID 내림차순 — 등록 태그 정확히 일치 (BR-10 BR-11 BR-12 BR-13)', async () => {
      expect(await search({ tag: '계산', sort: 'id', dir: 'desc' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 명시 — 난이도 5 문항이 나오는 유일한 경로 (BR-02 BR-03 BR-01)', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('난이도 6 — 1~5 바로 밖 (BR-04)', async () => {
      expect(await search({ level: '6' })).toMatchSnapshot();
    });

    it('키워드 정확히 100자 — 자르기 경계 안쪽 (BR-05)', async () => {
      expect(await search({ q: KEYWORD_100 })).toMatchSnapshot();
    });

    it('키워드 101자 — 자르기 경계 바깥 (BR-05)', async () => {
      expect(await search({ q: KEYWORD_101 })).toMatchSnapshot();
    });

    it('키워드 한 글자 — 한 글자 경고 경계 (BR-05 BR-06)', async () => {
      expect(await search({ q: '분' })).toMatchSnapshot();
    });

    it('기본 조건 결과 20건에서 2페이지 — 행 없음 · 건수 유지 (BR-02 BR-15 BR-16 BR-17)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('페이지 999 — 상한 그대로 (BR-15)', async () => {
      expect(await search({ page: '999' })).toMatchSnapshot();
    });

    it('페이지 1000 — 상한 초과는 999 로 고정 (BR-15)', async () => {
      expect(await search({ page: '1000' })).toMatchSnapshot();
    });

    it('페이지 0 — 경고 없이 1페이지 (BR-15)', async () => {
      expect(await search({ page: '0' })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 없음 — 첫 화면 기본 조건 (BR-02 BR-12 BR-15 BR-16 BR-17 BR-18)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('모든 파라미터 빈 문자열 — 키는 있고 값만 비어 있음 (BR-02 BR-12 BR-13 BR-15)', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });

    it('공백만 입력 — trim 뒤 조건 없음 (BR-02 BR-05 BR-07)', async () => {
      expect(await search({ q: '   ', level: ' ', unit: ' ' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('없는 단원 코드 Z9-99 — 형식은 맞고 미등록 (BR-07 BR-09)', async () => {
      expect(await search({ unit: 'Z9-99' })).toMatchSnapshot();
    });

    it('음수 페이지 · 음수 난이도 -1 (BR-04 BR-15)', async () => {
      expect(await search({ page: '-1', level: '-1' })).toMatchSnapshot();
    });

    it('태그 200자 — 50자로 자른 뒤 정확히 일치 (BR-10 BR-11)', async () => {
      expect(await search({ tag: TAG_200 })).toMatchSnapshot();
    });

    it('난이도 3abc — 정수 변환으로 난이도 3 결과 (BR-04)', async () => {
      expect(await search({ level: '3abc' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 m5-1 — 대소문자 무시 비교 (BR-07 BR-08)', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('형식이 틀린 단원 코드 ABC — 경고만 하고 그대로 조회 (BR-07 BR-09)', async () => {
      expect(await search({ unit: 'ABC' })).toMatchSnapshot();
    });

    it('키워드 % — 이스케이프 없는 와일드카드 (BR-06)', async () => {
      expect(await search({ q: '%' })).toMatchSnapshot();
    });

    it('키워드 _ — 한 글자 와일드카드 (BR-06)', async () => {
      expect(await search({ q: '_' })).toMatchSnapshot();
    });

    it('목록에 없는 정렬 price · 방향 up — 기본 정렬로 대체 (BR-12 BR-13)', async () => {
      expect(await search({ sort: 'price', dir: 'up' })).toMatchSnapshot();
    });

    it('키워드를 배열로 전달 q[]=분수&q[]=소수 — 첫 값만 사용 (BR-18)', async () => {
      expect(await search({ 'q[]': ['분수', '소수'] })).toMatchSnapshot();
    });
  });
});
