# 에이전트 네이티브 메모리 스토어

AI 에이전트(Claude Code 등)가 세션을 넘어 기억을 쓰고 회수하는 전용 메모리 서버입니다. 논문 arXiv:2606.24775의 4모듈 구조를 구현하되, 기존 RAG와 갈라지는 축인 유지보수 모듈(충돌 해소, 감쇠/망각, 정정 전파)에 비중을 둡니다.

## 문제

AI 에이전트는 세션이 끝나면 다 잊습니다. 현재 해결책은 조잡합니다 - CLAUDE.md 파일 몇 개, 또는 대화 로그를 벡터DB에 통째로 던지기. 결과적으로 같은 걸 매번 다시 알려줘야 하고, 몇 달 전 결정은 검색이 안 되고, 잘못된 기억이 낡은 채로 계속 회수됩니다.

## 아키텍처 (4모듈)

1. 표현/저장 - 세션 원문 로그를 무손실 보관하고, 추출된 사실(fact)을 출처 발화 링크 및 상태 이력과 함께 저장합니다. utterance 로그는 DB 트리거로 UPDATE/DELETE가 차단되어 append-only입니다.
2. 추출 - 세션 로그에서 LLM으로 기억 가치 있는 사실만 추출합니다. 초기 confidence는 명시 0.9 / 추론 0.6, 임베딩 유사도 0.92 이상이면 중복으로 보고 기존 사실을 확인 처리(dedupe)합니다.
3. 검색/라우팅 - `score = 0.55*유사도 + 0.25*최근성 + 0.20*신뢰도`로 랭킹하고, 유형별 반감기로 최근성을 감쇠시키며, 토큰 예산 안에서 그리디 패킹합니다.
4. 유지보수 - 차별화 핵심. 모순 충돌 해소(명시적 정정 > 최신 세션 > 최신 발화), 유형별 반감기 소프트 망각(expired, 삭제 아님), 정정 전파(관계 그래프 깊이 2까지 needs_review 플래그 후 재평가)를 수행합니다.

MCP 서버로 7개 툴을 노출하므로 모든 MCP 클라이언트가 이 메모리를 쓸 수 있습니다.

## 스택

Java 21, Spring Boot 3.3, PostgreSQL 16 + pgvector, Flyway, Testcontainers. LLM은 Claude Messages API, 임베딩은 프로바이더 인터페이스로 추상화했습니다. 테스트와 로컬 실행은 결정적 fake 프로바이더로 외부 키 없이 동작합니다.

## 실행 방법

### 1. 데이터베이스 기동

```bash
docker compose up -d
```

pgvector 확장이 포함된 PostgreSQL 16이 5432 포트로 뜹니다.

### 2. 테스트

```bash
./gradlew test
```

단위 테스트는 키 없이 통과합니다. 통합 테스트는 Docker가 있으면 Testcontainers로 실행되고, 없으면 자동 skip됩니다. 외부에서 관리하는 DB를 쓰려면 `-Dmemstore.test.jdbcUrl=jdbc:postgresql://localhost:5432/memstore`를 넘깁니다.

### 3. 서버 기동

```bash
./gradlew bootRun
```

`/actuator/health`가 UP을 반환합니다.

### 4. MCP 등록

MCP 서버는 stdio 전송을 씁니다. Claude Code 등에 등록:

```bash
claude mcp add memstore -- java -Dmemstore.mcp.stdio=true -jar build/libs/agent-memory-store-0.1.0.jar
```

노출 툴 7개: `memory_log_append`, `memory_extract`, `memory_search`, `memory_save_fact`, `memory_correct`, `memory_forget`, `memory_inspect`.

### 5. 데모 실행

```bash
bash demo/run-demo.sh
```

기본은 fake 프로바이더(결정적, 키 불필요)로 동작합니다. 실제 모델로 돌리려면 `MEMSTORE_LLM_PROVIDER=claude`, `EMBEDDING_PROVIDER=openai`와 각 API 키를 환경변수로 지정합니다.

## 데모 출력 예시

3월/5월/6월 3개 세션을 시드하고 4개 질의를 실행합니다. 3월 "PostgreSQL 15 + AWS", 5월 "스페이스 선호", 6월 "PostgreSQL 17로 마이그레이션 + 선호 정정"입니다.

```
[Q1 | conflict resolution] query: 'PostgreSQL version'
  - [decision] We decided to migrate to PostgreSQL version 17 (conf=0.90, score=0.835)
  - [decision] We decided to deploy on AWS (conf=0.90, score=0.709)
  - [preference] The team prefers tabs over spaces for indentation (conf=0.95, score=0.708)

[Q2 | provenance + supersede history] inspect the superseded PostgreSQL 15 fact
  statement: We decided to use PostgreSQL version 15
  status: superseded  superseded_by: 3b532f64-...
  provenance: session=710151a7-... seq=0 "We decided to use PostgreSQL version 15"

[Q3 | budget packing] query: 'project decisions' budget_tokens=20
  estimated_tokens=14
  - [preference] The team prefers tabs over spaces for indentation (conf=0.95, score=0.718)

[Q4 | correction reflected] query: 'indentation preference'
  - [preference] The team prefers tabs over spaces for indentation (conf=0.95, score=0.775)
  ...
```

Q1은 PostgreSQL 질의에 17을 최상위로 반환하고 15는 반환하지 않습니다(supersede됨). Q2의 inspect가 15 사실의 superseded 상태, 승자 포인터, 출처 발화를 보여줍니다. Q3은 낮은 예산에서 상위 1건만 패킹됩니다. Q4는 정정된 선호(탭)가 반영됩니다.

## 벤치마크

MemFail 스타일 시나리오 30개(모순 갱신 10, 명시적 정정 8, 감쇠/노후 6, 출처 추적 3, 예산/라우팅 3)를 하니스로 실행합니다.

```bash
./gradlew benchRun
```

결과는 `bench-report.md`에 기록됩니다. fake 프로바이더 기준 최근 실행 결과:

| Category | Passed / Total | Rate |
|---|---|---|
| contradiction | 10 / 10 | 100% |
| correction | 8 / 8 | 100% |
| decay | 6 / 6 | 100% |
| provenance | 3 / 3 | 100% |
| budget | 3 / 3 | 100% |
| overall | 30 / 30 | 100% |

MemFail 원본 데이터셋/코드 조사 기록은 `bench/MEMFAIL-NOTES.md`에 있습니다. 실제 모델 실행은 키를 요구하며 프로덕션 검증 경로입니다.

## 참고

- 논문: arXiv:2606.24775 (4모듈 프레임), arXiv:2605.26667 MemFail
- 포지션: "AI 도구 잘 쓰는 사람"이 아니라 "AI 인프라를 만드는 사람"
