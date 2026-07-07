# Plan 07: 감쇠와 소프트 망각

## 목표
SPEC R5(감쇠/망각)를 구현한다 - 유형별 반감기 기반 유효 신뢰도 감쇠와 expired 소프트 망각 유지보수 잡.

## 컨텍스트
- 선행: Plan 02 (FactStore.transition), Plan 05 (MemoryTypeConfig의 유형별 반감기 상수 공유, MemorySearchService)
- 명세: .planning/SPEC.md R5. 핵심 규칙:
  - 유효 신뢰도 = confidence × exp(-ln2 × last_confirmed_at 이후 경과일 / 유형별 반감기)
  - 반감기 기본: preference 180일, constraint 180일, decision 90일, event 30일, entity 365일 (Plan 05와 동일 설정 클래스)
  - 유효 신뢰도 0.15 미만 → status=expired (행 삭제 아님, invalidated_at 기록)
  - 검색 접근은 last_confirmed_at을 갱신하지 않음 - 재추출 confirm과 정정만 갱신 (반향실 방지)
  - 잡은 스케줄(예: 매일) + 수동 트리거 둘 다 지원. 감쇠 계산은 LLM 호출 없음
- 이 plan에서 `MaintenanceJob` 골격(스케줄러 + 수동 엔드포인트)을 만들고, Plan 08의 재평가 단계가 여기에 추가된다

## 작업
1. `DecayPolicy` - 순수 함수 (유형, confidence, 경과일 → 유효 신뢰도)
2. `MaintenanceJob` 골격 - 단계 리스트 실행 구조, 1단계로 감쇠 스캔 등록. Spring @Scheduled + 수동 트리거용 내부 엔드포인트
3. 감쇠 스캔 - 활성 fact 배치 순회, 유효 신뢰도 0.15 미만이면 expired 전이
4. 검색 경로 점검 - MemorySearchService가 last_confirmed_at을 갱신하지 않음을 테스트로 고정
5. 통합 테스트 - last_confirmed_at 과거 조작 시나리오

## must-haves
- expired 전이가 삭제가 아님 - 행/provenance/relation 전부 보존
- 감쇠 스캔이 배치 처리로 fact 수에 선형 (10k에서 수 초 내)

## 검증
- [ ] R5-A1 유형별 반감기 감쇠 공식 단위 테스트 통과 (경계값: 반감기 정확히 1배 경과 시 0.5배)
- [ ] R5-A2 last_confirmed_at을 과거로 조작한 event 사실이 유지보수 실행 후 expired로 전환된다
- [ ] R5-A3 expired 후에도 행이 유지되고 include_inactive=true 검색으로 조회된다
- [ ] R5-A4 검색 접근이 last_confirmed_at을 갱신하지 않는다
- 테스트 명령: `./gradlew test --tests '*Decay*' --tests '*Maintenance*'`
