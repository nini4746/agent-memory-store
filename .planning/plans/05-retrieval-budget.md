# Plan 05: 검색 라우팅과 컨텍스트 예산

## 목표
SPEC R3(검색/라우팅)을 구현한다 - 유사도/최근성/신뢰도 가중 점수 랭킹과 토큰 예산 그리디 패킹.

## 컨텍스트
- 선행: Plan 02 (FactStore), Plan 03 (FactVectorSearch, 정규화 유사도). Plan 04와는 독립이라 병렬 가능
- 명세: .planning/SPEC.md R3. 핵심 규칙:
  - score = 0.55×sim + 0.25×rec + 0.20×conf (가중치는 설정값, 이 값이 기본)
  - rec = exp(-ln2 × last_confirmed_at 이후 경과일 / 유형별 반감기). 반감기 기본값: preference 180, constraint 180, decision 90, event 30, entity 365 (일 단위, 설정값) - Plan 07의 감쇠와 동일 상수를 공유하도록 설정 클래스로 분리
  - needs_review fact는 conf에 0.5배 페널티 (R6-A3의 근거 지점이며 검증은 Plan 08 몫)
  - 예산: budget_tokens 기본 2000, 상한 8000. 토큰 추정 = ceil(문자수/4 × 1.1). 점수 내림차순 그리디, 안 들어가면 스킵하고 다음 시도
  - 기본 필터 status=active, include_inactive/type 필터 지원
  - 검색은 last_confirmed_at을 갱신하지 않는다 (검증 자체는 Plan 07의 R5-A4)

## 작업
1. `MemoryTypeConfig` - 유형별 반감기 상수 (설정 프로퍼티)
2. `ScoringPolicy` - 순수 함수로 점수 계산 (단위 테스트 대상)
3. `TokenEstimator` - ceil(chars/4 × 1.1)
4. `BudgetPacker` - 점수순 그리디 패킹, 스킵 로직, 전부 불가 시 빈 목록 + 경고 플래그
5. `MemorySearchService.search(query, budgetTokens?, types?, includeInactive?)`:
   - 쿼리 임베딩 → 벡터 topK(여유 있게 k=50) → 점수 재랭킹 → 패킹
   - 응답: 사실 목록(statement, type, confidence, fact_id, provenance utterance ids), 추정 사용 토큰, 경고 필드
6. 성능 테스트 - fake 임베딩으로 10k fact 시드, 검색 내부 처리(쿼리 임베딩 제외) 시간 측정 p95 산출
7. property 테스트 (jqwik 또는 랜덤 시드 반복 100회) - 임의 fact 크기/예산 조합에서 합계 ≤ 예산

## must-haves
- 점수/패킹이 순수 함수라 DB 없이 단위 테스트 가능
- 반감기 상수가 Plan 07과 공유되는 단일 설정 클래스에 존재

## 검증
- [ ] R3-A1 가중 점수 공식이 고정 입력에 대해 기대 랭킹을 내는 단위 테스트 통과
- [ ] R3-A2 임의 입력 100케이스에서 응답 추정 토큰 합이 예산을 초과하지 않는다 (property 테스트)
- [ ] R3-A3 예산보다 큰 단일 사실은 건너뛰고 다음을 채우며, 아무것도 못 채우면 빈 결과 + 경고 필드 반환
- [ ] R3-A4 기본 검색이 superseded/retracted/expired 사실을 반환하지 않는다
- [ ] R3-A5 10,000개 사실 시드 후 검색 내부 처리 p95가 500ms 미만이다
- 테스트 명령: `./gradlew test --tests '*Scoring*' --tests '*Budget*' --tests '*Search*'`
