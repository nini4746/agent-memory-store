# 0002. 소프트 상태와 DB 트리거로 강제하는 불변 원문 로그

- 상태: 채택
- 날짜: 2026-07-07

## 맥락
유지보수 모듈은 사실을 대체, 철회, 망각합니다. 이 연산이 원문을 잃게 하면 출처 추적과 잘못된 망각의 복구가 불가능합니다. SPEC은 원문 로그 무손실(R1)과 원문 수정/삭제 경로 금지(N1)를 요구합니다.

## 결정
- fact는 행을 삭제하지 않고 `status`(active | superseded | retracted | expired)로만 상태를 바꿉니다. `superseded_by`, `invalidated_at`으로 이력을 남깁니다.
- utterance는 append-only입니다. `V3__utterance_immutable_trigger.sql`의 `block_utterance_mutation()` 트리거가 UPDATE와 DELETE를 DB에서 거부합니다.
- `UtteranceRepository`도 save/find만 노출합니다.
- 모든 fact는 `fact_provenance`로 1개 이상의 utterance에 연결됩니다. `memory_save_fact`와 `memory_correct`는 합성 세션/발화를 만들어 이 불변식을 지킵니다.

## 대안
- 물리 삭제: 이력과 출처를 inspect로 추적할 수 없고, 잘못된 망각을 되짚을 수 없습니다.
- 애플리케이션 계층에서만 막기: 어떤 코드 경로가 원문을 바꿀 수 있는 여지가 남습니다.

## 결과
- 장점: `memory_inspect`로 진술, 상태, provenance, supersede 이력을 언제든 조회합니다.
- 장점: 유지보수 연산 후에도 utterance 행 수가 줄지 않습니다.
- 단점/감수한 점: 행이 계속 쌓입니다.
- 단점/감수한 점: 트리거는 행 단위 UPDATE/DELETE만 막고 TRUNCATE는 통과합니다. 벤치 하니스, 데모 러너, 테스트 정리가 의도적으로 TRUNCATE를 씁니다.
