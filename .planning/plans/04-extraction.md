# Plan 04: 추출 파이프라인 - LLM 사실 추출과 dedupe

## 목표
SPEC R2(추출)를 구현한다 - 세션 로그에서 LLM으로 사실을 추출하고, 출처 링크를 유지하며, 동일 사실은 확인 처리로 dedupe한다.

## 컨텍스트
- 선행: Plan 02 (SessionLogService, FactStore), Plan 03 (EmbeddingProvider, FactVectorSearch)
- 명세: .planning/SPEC.md R2. 핵심 규칙:
  - LLM(Claude API, temperature 0)이 세션 발화들에서 statement/type/명시성/출처 발화 seq 목록을 JSON으로 산출
  - 초기 confidence: 명시적 0.9, 추론 0.6
  - dedupe: 기존 활성 fact와 임베딩 유사도 0.92 이상이면 LLM으로 동일성 확인 → 동일하면 신규 생성 없이 기존 confirm (confidence +0.1 상한 0.95, last_confirmed_at 갱신)
- LLM 클라이언트는 이 plan에서 처음 만든다 - 인터페이스로 추상화하고 테스트는 canned 응답 fake 사용

## 작업
1. `LlmClient` 인터페이스 + Claude API 구현 (환경변수 ANTHROPIC_API_KEY, MEMSTORE_LLM_MODEL) + 테스트용 fake
2. 추출 프롬프트 - 기억 가치 기준(선호/제약/결정/사건/개체), 출처 발화 seq 필수, 기억 가치 없으면 빈 배열
3. `ExtractionService.extract(sessionId)`:
   - 세션 발화 로드 → LLM 호출 → 후보 파싱(검증 실패 항목은 스킵 + 로그)
   - 후보별 임베딩 → 유사도 0.92 이상 기존 활성 fact 있으면 LLM 동일성 확인 → confirm 또는 신규 저장
   - 신규 저장 시 provenance = LLM이 지목한 발화들 (없으면 해당 후보 거부)
   - 반환: 신규 n건, confirm m건, 스킵 k건 요약
4. 충돌 감지 훅 자리 마련 - 신규 저장 직전 `ConflictResolver` 인터페이스 호출 지점만 두고, 이번 plan에서는 no-op 구현 (Plan 06이 교체)
5. 테스트 - fake LLM + fake 임베딩으로 R2 수용 기준 재현

## must-haves
- provenance 없는 후보는 저장되지 않고 거부됨
- LLM 응답 JSON 파싱 실패가 전체 추출을 죽이지 않음 (항목 단위 스킵)

## 검증
- [ ] R2-A1 데모 세션 로그 입력 시 preference, constraint, decision 유형 사실이 각 1개 이상 추출된다 (fake LLM canned 응답 기반 통합 테스트)
- [ ] R2-A2 추출된 모든 fact가 1개 이상의 provenance 링크를 갖는다
- [ ] R2-A3 기억 가치 없는 세션 입력 시 0건 추출되고 오류가 없다
- [ ] R2-A4 동일 사실 재추출 시 새 fact 없이 기존 fact가 confirm된다 (confidence 증가, last_confirmed_at 갱신)
- 테스트 명령: `./gradlew test --tests '*Extraction*'`
