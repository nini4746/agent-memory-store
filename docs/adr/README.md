# 설계 결정 기록 (ADR)

agent-memory-store의 주요 설계 결정을 Michael Nygard 형식으로 기록합니다. 전체 설계는 [SDS](../SDS.md)를 봅니다.

| 번호 | 결정 | 상태 | 날짜 |
|---|---|---|---|
| [0001](0001-postgresql-pgvector-single-store.md) | Java/Spring 서버와 PostgreSQL + pgvector 단일 저장소 | 채택 | 2026-07-07 |
| [0002](0002-soft-state-and-immutable-utterance.md) | 소프트 상태와 DB 트리거로 강제하는 불변 원문 로그 | 채택 | 2026-07-07 |
| [0003](0003-provider-abstraction-fake-default.md) | LLM/임베딩 프로바이더 추상화와 fake 기본값 | 채택 | 2026-07-07 |
| [0004](0004-weighted-scoring-greedy-budget-packing.md) | 가중 점수 랭킹과 토큰 예산 그리디 패킹 | 채택 | 2026-07-07 |
| [0005](0005-conflict-resolution-priority-rules.md) | 모순 기억 충돌 해소 우선순위 규칙 | 채택 | 2026-07-07 |
| [0006](0006-type-half-life-decay-no-search-refresh.md) | 유형별 반감기 감쇠와 검색 비갱신 | 채택 | 2026-07-07 |
| [0007](0007-bounded-correction-propagation.md) | 의존 간선 깊이 2 정정 전파와 상한 있는 재검토 | 채택 | 2026-07-07 |
| [0008](0008-direct-mcp-json-rpc.md) | MCP JSON-RPC 직접 구현 (stdio) | 채택 | 2026-07-07 |
