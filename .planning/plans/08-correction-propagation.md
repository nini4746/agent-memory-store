# Plan 08: 정정 전파와 재평가

## 목표
SPEC R6(정정 전파)을 구현한다 - 무효화된 사실의 관계 이웃(깊이 2)을 needs_review로 플래그하고, 유지보수 잡이 상한 내에서 LLM 재평가한다.

## 컨텍스트
- 선행: Plan 06 (CorrectionService의 no-op `PropagationService` 훅 - 이 plan이 실 구현으로 교체), Plan 07 (MaintenanceJob 단계 구조)
- 명세: .planning/SPEC.md R6. 핵심 규칙:
  - 트리거: fact가 retracted 또는 superseded 될 때
  - 전파: fact_relation 그래프를 깊이 2까지 탐색 (visited 집합으로 순환 안전), 도달한 active fact에 needs_review=true
  - 재평가: MaintenanceJob 단계로 추가 - needs_review fact를 실행당 최대 20건 LLM(temperature 0)으로 현재 활성 사실 집합과 대조. 결과: 유지(플래그 해제) | supersede | retract. 미처리 건은 플래그 유지
  - 상한: 잡 1회 실행 LLM 호출 총 50회 이하 (재평가 20건 + 여유분, 호출 카운터로 강제)
  - needs_review는 검색 점수에서 conf 0.5배 페널티 (구현은 Plan 05 ScoringPolicy에 이미 존재, 여기서 검증)
- 상태 전이는 FactStore.transition, LLM fake는 기존 것 재사용

## 작업
1. `PropagationService` 실 구현 - BFS 깊이 2, visited 집합, 방향 무관(from/to 양방향) 이웃 탐색
2. Plan 06의 CorrectionService와 supersede 경로(ConflictResolver)에 전파 호출 연결
3. `ReviewStep` - MaintenanceJob 단계 추가: needs_review 오래된 순 20건 로드 → 각 건을 관련 활성 사실과 함께 LLM 판정 → 전이 또는 플래그 해제
4. `LlmCallBudget` - 잡 실행 스코프 카운터, 50회 도달 시 이후 호출 차단하고 잔여 건 스킵
5. 통합 테스트 - refines 체인, 순환 관계, 상한 시나리오

## must-haves
- 전파가 needs_review 플래그만 세움 - 전파 단계에서 직접 retract/supersede 하지 않음 (판정은 재평가 단계 몫)
- LLM 판정 파싱 실패 시 해당 건 플래그 유지 (안전 측)

## 검증
- [ ] R6-A1 A를 refines하는 B가 있을 때 A 정정 시 B가 needs_review로 플래그된다
- [ ] R6-A2 순환 관계(A↔B)에서 전파가 무한루프 없이 종료된다
- [ ] R6-A3 needs_review 사실이 검색 점수에서 신뢰도 0.5배 페널티를 받는다 (랭킹 비교 테스트)
- [ ] R6-A4 유지보수 잡 1회 실행의 LLM 호출이 50회 이하다 (카운터 검증 테스트, 재평가 20건 이하 포함)
- 테스트 명령: `./gradlew test --tests '*Propagation*' --tests '*Review*'`
