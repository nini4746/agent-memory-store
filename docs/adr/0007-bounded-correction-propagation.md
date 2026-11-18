# 0007. 의존 간선 깊이 2 정정 전파와 상한 있는 재검토

- 상태: 채택
- 날짜: 2026-07-07

## 맥락
사실이 철회되거나 대체되면 그 사실에 기대던 다른 사실도 의심해야 합니다. 관계 그래프에는 순환이 있을 수 있고, 유지보수 잡의 LLM 호출은 무제한이면 안 됩니다(N5).

## 결정
- 무효화 시 `DefaultPropagationService`가 `fact_relation`을 visited 집합으로 순환 안전하게 깊이 2까지 탐색해, 닿은 활성 사실을 needs_review로 플래그합니다.
- 따라가는 간선은 refines와 duplicates뿐입니다. contradicts와 supersedes는 해소 기록이라 따라가지 않습니다.
- 전파는 플래그만 설정합니다. 실제 재판정은 유지보수 잡의 `ReviewStep`이 합니다: 실행당 최대 20건(`review-batch-size`), 실행당 LLM 호출 상한 50회(`llm-call-budget`). 결과는 유지(플래그 해제) | supersede | retract이고, 미처리 건은 플래그를 유지한 채 다음 실행을 기다립니다.

## 대안
- 모든 관계 간선 순회: contradicts/supersedes를 따라가면 승자 fact가 곧바로 needs_review가 됩니다.
- 깊이 2 초과 그래프 추론: 범위 외입니다.

## 결과
- 장점: 순환 관계에서도 전파가 종료되고, 유지보수 1회의 LLM 비용에 상한이 있습니다.
- 장점: 미처리 needs_review 사실은 검색에서 0.5배 페널티(0004)를 받아 안전 측으로 동작합니다.
- 단점/감수한 점: SPEC 문면("fact_relation 그래프를 깊이 2까지")보다 좁게 순회합니다.
- 단점/감수한 점: `ReviewStep`의 supersede 판정은 대체 사실을 지목하지 않아 `superseded_by` 없이 전이됩니다.
