# 에이전트 네이티브 메모리 스토어 명세

작성일: 2026-07-07. spec-goal-flow 자문자답 인터뷰 결과물. 1인 개발 전제.

## 개요

AI 에이전트(Claude Code 등)가 세션을 넘어 기억을 쓰고 회수하는 전용 메모리 서버.
논문 arXiv:2606.24775의 4모듈 구조(표현/저장, 추출, 검색/라우팅, 유지보수)를 구현하되,
유지보수 모듈(모순 충돌 해소, 감쇠/망각, 정정 전파)에 비중을 둔다 - 기존 RAG와의 차별화 축.

- 스택: Java 21 + Spring Boot 3.x, PostgreSQL 16 + pgvector, Flyway, docker-compose. MCP 서버로 노출(Spring AI MCP server starter 우선, 불가 시 JSON-RPC 직접 구현)
- LLM: Claude API(모델 id는 설정값), 추출/분류는 temperature 0
- 임베딩: 프로바이더 인터페이스로 추상화. 기본은 환경변수로 지정(예: text-embedding-3-small 1536차원). 테스트는 결정적 fake 임베딩 사용
- 검증: MemFail(arXiv:2605.26667) 스타일 시나리오 스위트로 "N개 중 M개 정답" 수치화

## 최종 모호성 점수표

| 차원 | 점수 | 최소 기준 | 판정 |
|---|---|---|---|
| 목표 명확성 | 0.85 | 0.75 | 통과 |
| 경계 명확성 | 0.85 | 0.70 | 통과 |
| 제약 명확성 | 0.75 | 0.65 | 통과 |
| 수용 기준 | 0.85 | 0.70 | 통과 |

모호성 = 1.0 - (0.35×0.85 + 0.25×0.85 + 0.20×0.75 + 0.20×0.85) = **0.17** (게이트 ≤ 0.20 통과)

## 용어와 핵심 스키마

- session: 에이전트 대화 세션. id, source, started_at, ended_at, metadata(jsonb)
- utterance: 세션 내 원문 발화. id, session_id, seq, role, content, created_at. unique(session_id, seq). 무손실 보관 대상
- fact: 추출된 사실. id, statement, type(preference | constraint | decision | event | entity), embedding(vector), embedding_model, confidence(0~1), status(active | superseded | retracted | expired), needs_review(bool), created_at, last_confirmed_at, invalidated_at, superseded_by(fact id)
- fact_provenance: fact ↔ utterance 다대다 링크. 모든 fact는 1개 이상 필수
- fact_relation: fact 간 관계. relation(contradicts | supersedes | refines | duplicates)

상태 의미: superseded = 더 새로운 사실로 대체됨, retracted = 명시적 정정/망각으로 철회됨, expired = 감쇠로 소프트 망각됨. 세 상태 모두 삭제가 아니며 행은 영구 보존.

## 요구사항

### R1. 표현/저장 - 무손실 로그와 출처 연결 사실

- 검증 문장: 시스템은 세션 원문 로그를 무손실 보관하고, 추출된 사실을 출처 발화 링크와 상태 이력과 함께 저장한다.
- 현재 상태: 코드 없음. README만 존재.
- 목표 상태: 위 스키마의 Postgres 테이블(Flyway 마이그레이션) + 저장/조회 서비스 계층.
- 수용 기준:
  - [ ] R1-A1 세션 로그 append 후 원문이 바이트 동일하게 재조회된다
  - [ ] R1-A2 감쇠/망각/정정 등 어떤 유지보수 연산 후에도 utterance 행 수가 감소하지 않는다 (통합 테스트)
  - [ ] R1-A3 fact의 status, confidence, last_confirmed_at, superseded_by가 저장되고 상태 전이가 기록된다
  - [ ] R1-A4 fact_provenance로 fact → 출처 utterance 역참조 조회가 동작한다

### R2. 추출 - LLM 기반 사실 추출과 출처 유지

- 검증 문장: 세션 로그에서 LLM으로 기억 가치 있는 사실만 추출하며, 추출된 모든 사실은 출처 발화에 링크된다.
- 현재 상태: 없음.
- 목표 상태: 세션 단위 추출 파이프라인 - LLM이 statement/type/명시성 점수를 산출하고, 기존 사실과 중복 판정(임베딩 유사도 0.92 이상 + LLM 확인) 후 신규 저장 또는 기존 확인 처리.
- 초기 confidence 규칙: 명시적 진술 0.9, 추론된 사실 0.6. 재확인 시 +0.1 (상한 0.95, last_confirmed_at 갱신).
- 수용 기준:
  - [ ] R2-A1 데모 세션 로그 입력 시 preference, constraint, decision 유형 사실이 각 1개 이상 추출된다
  - [ ] R2-A2 추출된 모든 fact가 1개 이상의 provenance 링크를 갖는다 (DB 제약 또는 서비스 계층 강제 + 테스트)
  - [ ] R2-A3 기억 가치 없는 세션(잡담만) 입력 시 0건 추출되고 오류가 없다
  - [ ] R2-A4 동일 사실 재추출 시 새 fact를 만들지 않고 기존 fact를 확인 처리한다 (confidence 증가, last_confirmed_at 갱신)

### R3. 검색/라우팅 - 가중 점수와 컨텍스트 예산

- 검증 문장: 검색은 유사도 0.55 + 최근성 0.25 + 신뢰도 0.20 가중 점수로 랭킹하고, 지정된 토큰 예산을 초과하지 않는 결과 묶음을 반환한다.
- 현재 상태: 없음.
- 목표 상태:
  - 점수: score = 0.55×sim + 0.25×rec + 0.20×conf. sim은 코사인 유사도를 [0,1] 정규화, rec = exp(-ln2 × 경과일 / 유형별 반감기), conf는 현재 confidence. needs_review 사실은 conf에 0.5배 페널티. 가중치는 설정값이며 위 값이 기본.
  - 예산: budget_tokens 파라미터(기본 2000, 상한 8000). 토큰 추정 = ceil(문자수/4 × 1.1). 점수 내림차순 그리디 패킹, 예산에 안 들어가는 사실은 건너뛰고 다음 사실 시도.
  - 기본 필터: status = active만. include_inactive=true 시 전체 조회 가능. type 필터 지원.
- 수용 기준:
  - [ ] R3-A1 가중 점수 공식이 고정 입력에 대해 기대 랭킹을 내는 단위 테스트 통과
  - [ ] R3-A2 임의 입력 조합에서 응답의 추정 토큰 합이 예산을 초과하지 않는다 (property 테스트 100케이스)
  - [ ] R3-A3 예산보다 큰 단일 사실은 건너뛰고 다음 사실을 채우며, 아무것도 못 채우면 빈 결과 + 경고 필드를 반환한다
  - [ ] R3-A4 기본 검색이 superseded/retracted/expired 사실을 반환하지 않는다
  - [ ] R3-A5 10,000개 사실 시드 후 검색 내부 처리(외부 임베딩 API 호출 제외)의 p95가 500ms 미만이다

### R4. 충돌 해소 - 모순 기억 우선순위 규칙

- 검증 문장: 새 사실이 기존 활성 사실과 모순되면 정해진 우선순위 규칙(명시적 정정 > 최신 세션 > 최신 발화)에 따라 한쪽이 supersede 또는 retract 된다.
- 현재 상태: 없음.
- 목표 상태:
  - 감지: 추출 시 임베딩 유사도 0.80 이상 상위 5개 활성 사실과 LLM 쌍 분류 - duplicate | contradicts | refines | unrelated.
  - duplicate → 기존 사실 확인 처리(신규 생성 없음). refines → 신규 저장 + refines 관계, 둘 다 active.
  - contradicts → 최신 세션의 사실 승리, 패자는 superseded + superseded_by 설정. 같은 세션이면 나중 발화 승리. 같은 발화 내 모순이면 둘 다 저장하고 둘 다 needs_review.
  - 명시적 정정(memory_correct 호출 또는 추출이 정정 화행 감지) → 대상 사실 retracted, 정정 사실 confidence 0.95, 정정 전파(R6) 트리거.
- 수용 기준:
  - [ ] R4-A1 "3월 세션에서 A, 6월 세션에서 B" 시나리오에서 검색이 B만 반환하고 A는 superseded + superseded_by가 B를 가리킨다
  - [ ] R4-A2 같은 세션 내 모순은 나중 발화의 사실이 승리한다
  - [ ] R4-A3 같은 발화 내 모순은 둘 다 저장되고 둘 다 needs_review 플래그를 갖는다
  - [ ] R4-A4 retracted 사실과 동일한 진술이 이후 세션에서 다시 나타나면 자동으로 active 부활하지 않고 needs_review로 저장된다

### R5. 감쇠/망각 - 유형별 반감기 소프트 망각

- 검증 문장: 사실 유형별 반감기에 따라 유효 신뢰도가 감쇠하고, 임계치 0.15 미만이면 expired로 전환된다(삭제 아님).
- 현재 상태: 없음.
- 목표 상태: 유지보수 잡(스케줄 + 수동 트리거)이 유효 신뢰도 = confidence × exp(-ln2 × last_confirmed_at 이후 경과일 / 반감기)를 계산. 기본 반감기 - preference 180일, constraint 180일, decision 90일, event 30일, entity 365일 (설정값). 검색 접근은 last_confirmed_at을 갱신하지 않는다 - 재추출 확인 또는 정정만 갱신 (반향실 방지).
- 수용 기준:
  - [ ] R5-A1 유형별 반감기 감쇠 공식 단위 테스트 통과
  - [ ] R5-A2 last_confirmed_at을 과거로 조작한 event 사실이 유지보수 실행 후 expired로 전환된다
  - [ ] R5-A3 expired 전환 후에도 행이 유지되고 include_inactive=true 검색으로 조회된다
  - [ ] R5-A4 검색 접근이 last_confirmed_at을 갱신하지 않는다 (테스트)

### R6. 정정 전파 - 연결 사실 재검토

- 검증 문장: 사실이 retracted 또는 superseded 되면 관계로 연결된 사실(깊이 2 이내)이 needs_review로 플래그되고, 유지보수 잡이 상한 내에서 재평가한다.
- 현재 상태: 없음.
- 목표 상태: 무효화 이벤트 시 fact_relation 그래프를 깊이 2까지 순환 안전하게 탐색해 needs_review 설정. 유지보수 잡이 실행당 최대 20건을 LLM으로 현재 활성 사실 집합과 대조 재평가 - 결과는 유지(플래그 해제) | supersede | retract. 미처리 건은 플래그 유지 상태로 다음 실행 대기.
- 수용 기준:
  - [ ] R6-A1 A를 refines하는 B가 있을 때 A 정정 시 B가 needs_review로 플래그된다
  - [ ] R6-A2 순환 관계(A↔B)에서 전파가 무한루프 없이 종료된다
  - [ ] R6-A3 needs_review 사실은 검색 점수에서 신뢰도 0.5배 페널티를 받는다
  - [ ] R6-A4 유지보수 잡 1회 실행의 LLM 호출이 50회 이하다 (재평가 20건 이하 포함, 테스트 또는 카운터 검증)

### R7. MCP 인터페이스 - 7개 툴 노출

- 검증 문장: 아래 7개 MCP 툴이 노출되고 MCP 클라이언트에서 왕복 호출된다.
- 현재 상태: 없음.
- 목표 상태:
  1. memory_log_append(session_id?, source?, entries[]) → session_id - 원문 로그 무손실 저장
  2. memory_extract(session_id) → 추출 결과 요약 - 추출 파이프라인 트리거
  3. memory_search(query, budget_tokens?, types?, include_inactive?) → 예산 내 기억 묶음 + 출처 id
  4. memory_save_fact(statement, type) → fact_id - 직접 저장, 합성 세션/발화를 만들어 provenance 보장
  5. memory_correct(fact_id, correction_statement) → 대상 retract + 정정 사실 저장 + 전파 트리거
  6. memory_forget(fact_id, reason) → retract 처리, 대체 사실 없음
  7. memory_inspect(fact_id) → 진술, 상태, confidence, provenance(세션/발화), 관계, supersede 이력
- 수용 기준:
  - [ ] R7-A1 tools/list에 7개 툴과 파라미터 스키마가 노출된다
  - [ ] R7-A2 MCP 클라이언트(Claude Code 또는 MCP inspector)에서 memory_search 왕복 호출이 성공한다
  - [ ] R7-A3 memory_correct 호출이 R4 충돌 해소와 R6 전파 파이프라인을 실제로 트리거한다 (통합 테스트)
  - [ ] R7-A4 memory_save_fact로 저장된 fact도 합성 provenance를 가지며, provenance 없는 fact는 생성 불가하다

### R8. 벤치마크 - MemFail 스타일 정답률 수치화

- 검증 문장: MemFail 스타일 시나리오 스위트를 하니스로 실행해 카테고리별 M/N 정답률을 산출하고 전체 80% 이상을 달성한다.
- 현재 상태: 없음.
- 목표 상태: MemFail(arXiv:2605.26667) 원본 데이터/코드 사용 가능성을 먼저 조사하고, 불가 시 자체 스위트 제작 - YAML 시나리오 30개 이상: 모순 갱신 10, 명시적 정정 8, 감쇠/노후 6, 출처 추적 3, 예산/라우팅 3. 각 시나리오 = 시드 세션들 + 질의 + 기대 결과(가능하면 fact id/키워드 결정적 판정, 자유 텍스트만 LLM 저지). 하니스는 MCP 툴 경유로 시드/질의하고 bench-report.md를 생성.
- 수용 기준:
  - [ ] R8-A1 MemFail 원본 사용 가능성 조사 결과가 기록된다 (사용 가능하면 통합, 불가면 사유 기록 후 자체 스위트로 진행)
  - [ ] R8-A2 자체 스위트가 30개 이상이며 카테고리 구성(모순 10, 정정 8, 감쇠 6, 출처 3, 예산 3)을 충족한다
  - [ ] R8-A3 하니스 1회 실행으로 카테고리별 M/N 표가 담긴 bench-report.md가 생성된다
  - [ ] R8-A4 전체 정답률 80% 이상이고 모순 갱신 카테고리도 80% 이상이다

### R9. 데모 - 3세션 시나리오 재현 스크립트

- 검증 문장: 3세션 데모 시나리오가 스크립트 한 번 실행으로 재현되며 충돌 해소, 출처 추적, 예산 패킹을 보여준다.
- 현재 상태: 없음.
- 목표 상태: 데모 시나리오 - 3월 세션 "PostgreSQL 15 사용 결정" + "AWS 배포", 5월 세션 "탭 대신 스페이스 선호", 6월 세션 "PostgreSQL 17로 마이그레이션" + 선호 정정. 이후 질의 4개 - (1) Postgres 버전 → 17만, (2) inspect로 15의 superseded 이력, (3) 낮은 예산 검색으로 패킹 확인, (4) 정정된 선호가 반영된 결과.
- 수용 기준:
  - [ ] R9-A1 데모 스크립트 1회 실행으로 3세션 시드와 4개 질의 결과가 출력된다
  - [ ] R9-A2 "Postgres 버전" 질의에 17만 반환되고, inspect로 15의 superseded 이력과 출처 발화가 확인된다
  - [ ] R9-A3 README에 실행 방법과 데모 출력 예시가 갱신된다

## 부정 수용 기준 (must-NOT)

- [ ] N1 utterance(원문 로그)를 수정하거나 삭제하는 코드 경로가 존재하지 않는다 - DB 트리거로 UPDATE/DELETE 차단 + 차단 동작 테스트
- N2 기본 검색의 비활성 사실 반환 금지 → R3-A4로 검증
- N3 컨텍스트 예산 초과 금지 → R3-A2로 검증
- N4 provenance 없는 fact 생성 금지 → R2-A2, R7-A4로 검증
- N5 유지보수 잡의 무제한 LLM 호출 금지 → R6-A4로 검증

## 경계

### 범위 내

- 4모듈 전체: 저장(무손실 로그 + fact), 추출, 검색/라우팅(예산 포함), 유지보수(충돌/감쇠/전파)
- MCP 서버 노출 7개 툴
- MemFail 스타일 벤치마크 스위트 + 하니스 + 리포트
- 데모 시나리오 스크립트와 README 갱신
- docker-compose 기반 로컬 실행 환경

### 범위 외 (이유)

- 웹 UI/대시보드 - 소비자는 에이전트이며 inspect 툴로 충분
- 인증/멀티테넌시 - 로컬 단일 사용자 포트폴리오 범위 초과
- 임베디드(Rust/Go) 변형 - 스택은 Java/Spring 서버형으로 확정
- 임베딩 모델 교체 시 재임베딩 마이그레이션 - fact에 embedding_model을 기록해 혼합을 거부하는 것까지만. 마이그레이션 잡은 이후 과제
- 깊이 2 초과 그래프 추론/추론 체인 - 전파는 깊이 2 상한
- 실시간 스트리밍 추출 - 세션 단위 배치 추출로 충분
- MemFail 원본 점수 재현 - 원본 사용 가능하면 실행하되, 원본 지표 재현 자체는 목표 아님
- 다국어 임베딩 최적화 - 기본 프로바이더 성능에 위임
- 분산/샤딩/고가용성 - 단일 Postgres 전제

## 엣지 해소 기록

| 엣지 | 해소 |
|---|---|
| 같은 발화 내 모순 두 사실 | 명세 추가 - 둘 다 저장 + needs_review (R4-A3) |
| retracted 사실과 동일 진술 재등장 | 명세 추가 - 자동 부활 금지, needs_review 저장 (R4-A4) |
| 검색 접근이 감쇠를 리셋하는가 | 명세 추가 - 갱신 안 함, 재확인/정정만 갱신 (R5-A4, 반향실 방지) |
| 예산보다 큰 단일 사실 | 명세 추가 - 건너뛰고 다음, 전부 불가 시 빈 결과 + 경고 (R3-A3) |
| 토큰 추정 오차 | 테스트로 방어 - 문자수/4 × 1.1 보수 추정, property 테스트 (R3-A2) |
| 기억 가치 없는 세션 | 명세 추가 - 0건 추출, 무오류 (R2-A3) |
| 동일 사실 재추출 | 명세 추가 - 확인 처리로 dedupe (R2-A4) |
| 임베딩 프로바이더 교체로 차원 불일치 | 명세 추가 + 경계 - embedding_model 기록, 혼합 거부. 재임베딩은 범위 외 |
| 전파 그래프 순환 | 명세 추가 - visited 집합 + 깊이 2 상한 (R6-A2) |
| MemFail 원본 사용 불가 | 명세 반영 - 조사 후 자체 스위트 fallback (R8-A1, R8-A2) |

## 제약

- Java 21, Spring Boot 3.x, PostgreSQL 16 + pgvector, Flyway, docker-compose. 1인 개발, 로컬 실행 전제
- LLM은 Claude API, 모델 id/키는 환경변수. 추출과 분류는 temperature 0
- 임베딩은 프로바이더 인터페이스 + 환경변수 설정. 단위/통합 테스트는 결정적 fake 임베딩으로 외부 의존 없이 통과해야 함
- 검색 내부 처리 p95 500ms 미만 @ 10k 사실 (R3-A5)
- 유지보수 잡 LLM 호출 상한 50회/실행 (R6-A4)
- 벤치마크 실행은 실제 LLM/임베딩 키 필요 - CI 필수 아님, 로컬 실행 + 리포트 커밋 방식

## 셀프 그릴링 기록 (1패스)

- Q: confidence 초기값 근거는? → A: 명시적 0.9 / 추론 0.6, 재확인 +0.1 상한 0.95. R2에 반영
- Q: 모순 감지를 LLM에 전부 맡기면 벤치 점수가 흔들리지 않나? → A: 후보는 임베딩 유사도 0.80으로 좁히고 LLM은 temperature 0 쌍 분류만. 잔여 변동성은 80% 목표치에 흡수
- Q: 벤치 정답 판정 자동화 방법은? → A: 기대 fact id/키워드 결정적 판정 우선, 자유 텍스트만 LLM 저지. R8에 반영
- Q: MCP 서버를 Java로 어떻게? → A: Spring AI MCP server starter 우선, 불가 시 JSON-RPC 직접 구현. 제약에 반영
- Q: needs_review 미처리 건 누적되면? → A: 실행당 20건 상한, 잔여는 플래그 유지 + 0.5배 페널티로 안전 측 동작. R6에 반영
- Q: memory_save_fact가 provenance 불변식을 깨지 않나? → A: 합성 세션/발화 생성으로 불변식 유지. R7-A4로 검증
