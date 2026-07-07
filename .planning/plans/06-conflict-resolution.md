# Plan 06: 충돌 감지와 해소

## 목표
SPEC R4(충돌 해소)를 구현한다 - 모순 감지(임베딩 후보 + LLM 쌍 분류)와 우선순위 규칙(명시적 정정 > 최신 세션 > 최신 발화)에 따른 supersede/retract.

## 컨텍스트
- 선행: Plan 04 (ExtractionService에 no-op `ConflictResolver` 훅 존재 - 이 plan이 실 구현으로 교체)
- 명세: .planning/SPEC.md R4. 핵심 규칙:
  - 감지: 신규 후보와 임베딩 유사도 0.80 이상 상위 5개 활성 fact를 LLM(temperature 0)으로 쌍 분류 - duplicate | contradicts | refines | unrelated
  - duplicate → 기존 confirm (Plan 04의 confirm 로직 재사용)
  - refines → 신규 저장 + fact_relation(refines), 둘 다 active
  - contradicts → 최신 세션 승리. 패자 status=superseded, superseded_by=승자, fact_relation(contradicts) 기록. 같은 세션이면 나중 발화(utterance seq) 승리. 같은 발화면 둘 다 저장 + 둘 다 needs_review
  - 명시적 정정 경로: `CorrectionService.correct(factId, correctionStatement)` - 대상 retracted, 정정 fact confidence 0.95 저장(합성 또는 실제 provenance), 전파 트리거 지점(Plan 08에서 구현될 `PropagationService` 인터페이스 호출, 이번 plan은 no-op)
  - retracted와 동일 진술 재등장(유사도 0.92 이상, retracted 포함 비교) → 자동 active 부활 금지, 신규는 needs_review=true로 저장
- LLM/임베딩 테스트는 fake로 (Plan 03/04 기존 fake 재사용)

## 작업
1. `ConflictResolver` 실 구현 - 후보 조회(활성 + retracted 별도 조회), LLM 쌍 분류, 분기 처리
2. 승자 판정 유틸 - 세션 started_at 비교 → 동일 세션이면 utterance seq 비교 → 동일 발화면 무승부 처리
3. `CorrectionService` - retract + 정정 fact 저장 + PropagationService(no-op) 호출
4. 상태 전이는 Plan 02의 `FactStore.transition` 재사용
5. 시나리오 통합 테스트 4종 (R4-A1~A4 대응)

## must-haves
- 모든 supersede/retract 전이에 fact_relation과 superseded_by가 남아 inspect에서 이력 추적 가능
- LLM 분류 실패(파싱 불가) 시 안전 측 동작 - 신규 fact를 needs_review로 저장하고 기존은 건드리지 않음

## 검증
- [ ] R4-A1 3월 세션 A, 6월 세션 B 시나리오에서 검색이 B만 반환하고 A는 superseded + superseded_by=B다
- [ ] R4-A2 같은 세션 내 모순은 나중 발화의 사실이 승리한다
- [ ] R4-A3 같은 발화 내 모순은 둘 다 저장되고 둘 다 needs_review다
- [ ] R4-A4 retracted 사실과 동일 진술이 재등장하면 자동 부활 없이 needs_review로 저장된다
- 테스트 명령: `./gradlew test --tests '*Conflict*' --tests '*Correction*'`
