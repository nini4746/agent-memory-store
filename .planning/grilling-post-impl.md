# 사후 그릴링 (plan 12)

작성일: 2026-07-07. plan 01~11 구현 완료 후 SPEC.md의 R1~R9 + N1~N5를 코드·테스트와 대조한 기록.
방법: 수용 기준별로 구현 근거(file:line)와 "테스트가 실제로 그 기준을 검증하는가"를 심문. 갭은 소규모 결함(수정)과 설계 수준(목록화)으로 분리.

실행 환경 참고: 이 머신의 Docker Engine 29.4.3은 Testcontainers(docker-java)와 API 협상이 안 되어 통합 테스트가 전부 skip된다. `docker run pgvector/pgvector:pg16` (포트 55432) + `-Dmemstore.test.jdbcUrl=jdbc:postgresql://localhost:55432/memstore` 외부 DB 경로로 전체 스위트를 실행해 검증했다.

## 판정 요약

| 기준 | 판정 | 근거 |
|---|---|---|
| R1-A1 | 충족 | `SessionLogService.append` 원문 그대로 저장 (src/main/.../service/SessionLogService.java:64-70), 테스트 `r1a1` 개행/탭/이모지/백슬래시 케이스 (src/test/.../StorageIntegrationTest.java:34-42) |
| R1-A2 | 충족 | `r1a2` 전이 후 행 수 비교 (StorageIntegrationTest.java:45-60) |
| R1-A3 | 충족 | `FactStore.transition` invalidated_at/superseded_by 기록 (FactStore.java:111-122), `r1a3` (StorageIntegrationTest.java:63-83) |
| R1-A4 | 충족 | `FactStore.provenanceUtterances` (FactStore.java:165-171), `r1a4` (StorageIntegrationTest.java:86-99) |
| R2-A1 | 충족 | `ExtractionService.extract` (ExtractionService.java:58), `r2a1` 3유형 검증 (ExtractionIntegrationTest.java:41-53) |
| R2-A2 | 충족 | provenance 없는 후보 거부 (ExtractionService.java:86-91) + `FactStore.saveAt` 예외 (FactStore.java:69-72), `r2a2`, `n4` 테스트 |
| R2-A3 | 충족 | fake LLM 잡담 0건 (FakeLlmClient.java:54), `r2a3` (ExtractionIntegrationTest.java:68-78) |
| R2-A4 | 충족 | 0.92 dedupe + confirm (ExtractionService.java:112-122, FactStore.java:129-135), `r2a4` confidence 증가·last_confirmed_at 갱신 검증 |
| R3-A1 | 충족 | `ScoringPolicy.score` 0.55/0.25/0.20 (ScoringPolicy.java:36-44), `r3a1` 고정값+랭킹 (ScoringBudgetTest.java:21-41) |
| R3-A2 (N3) | 충족 | `BudgetPacker.pack` (BudgetPacker.java:28-47), jqwik property 100케이스 (BudgetPackerPropertyTest.java:22-35) |
| R3-A3 | 충족 | 스킵+빈결과 경고 (BudgetPacker.java:35-45), `r3a3` 2케이스 (ScoringBudgetTest.java:54-74) |
| R3-A4 (N2) | 충족 | 기본 status=ACTIVE 필터 (MemorySearchService.java:66-68), `r3a4` (SearchIntegrationTest.java:43-64) |
| R3-A5 | 충족(실측) | 10k 시드 후 30회 측정 p95 검증 테스트 존재 (SearchIntegrationTest.java:67-87). 이번 실행 실측: 아래 "p95 실측" 참조 |
| R4-A1 | 충족 | 최신 세션 승리 (DefaultConflictResolver.java:145-176), `r4a1` superseded_by 포인터까지 검증 |
| R4-A2 | 충족 | seq 비교 fallback (DefaultConflictResolver.java:178-184), `r4a2` |
| R4-A3 | 충족 | cmp==0 시 둘 다 needs_review (DefaultConflictResolver.java:152-158), `r4a3` |
| R4-A4 | 충족 | retracted 매칭 시 부활 금지 + needs_review 저장 (DefaultConflictResolver.java:59-62, 186-194), `r4a4` |
| R4 명시적 정정(추출 경로) | 갭 발견 → 수정 | 아래 "발견 결함 1" 참조 |
| R5-A1 | 충족 | `DecayPolicy` (DecayPolicy.java:23-27), 반감기 정확 1배 경과 0.5배 경계값 테스트 (DecayPolicyTest.java:17-27) |
| R5-A2 | 충족 | `DecayStep` 0.15 미만 expired 전이 (DecayStep.java:39-53), `r5a2_a3` last_confirmed_at 과거 조작 시나리오 |
| R5-A3 | 충족 | expired 후 행 보존 + include_inactive 조회 (`r5a2_a3` 후반부) |
| R5-A4 | 충족 | 검색 경로에 last_confirmed_at 쓰기 없음 (MemorySearchService.search는 readOnly 트랜잭션, :61), `r5a4` 테스트 |
| R6-A1 | 충족 | BFS 깊이 2 전파 (DefaultPropagationService.java:40-61), `r6a1` refines 체인 |
| R6-A2 | 충족 | visited 집합 (DefaultPropagationService.java:41-49), `r6a2` 순환 A↔B + @Timeout(20) |
| R6-A3 | 충족 | needs_review 0.5배 페널티 (ScoringPolicy.java:38-39), `r6a3` 동일 문장 랭킹 비교 |
| R6-A4 (N5) | 충족 | `LlmCallBudget` 실카운터 (LlmCallBudget.java:23-29) + `ReviewStep` 배치 20건 (ReviewStep.java:54-56, MaintenanceProperties 기본값 20/50), `r6a4` 60건 시드 후 lastLlmCalls ≤ 20 ≤ 50 검증. 카운팅 로직 실재 확인 |
| R7-A1 | 충족 | 7개 툴 + JSON 스키마 (McpServer.java:112-188), `r7a1` |
| R7-A2 | 부분 충족 | `r7a2` 왕복 테스트는 in-process `McpServer.handle` 경유. 외부 MCP 클라이언트(inspector/Claude Code) 실왕복 기록 없음. stdio 서버 존재 (McpStdioServer.java). 아래 설계 갭 4 |
| R7-A3 | 충족 | `r7a3` correct → 대상 retracted + refines 이웃 needs_review 확인 (McpIntegrationTest.java:97-111) |
| R7-A4 (N4) | 충족 | 합성 세션/발화 (McpToolService.java:123-130), `r7a4` + `n4` 서비스 계층 예외 |
| R8-A1 | 충족 | bench/MEMFAIL-NOTES.md에 조사·불가 사유·fallback 기록 |
| R8-A2 | 충족 | 시나리오 30개, 카테고리 10/8/6/3/3 (bench/scenarios/*.yaml, grep 카운트로 확인) |
| R8-A3 | 충족 | `BenchHarness.writeReport` M/N 표 + 모델/일시 기록 (BenchHarness.java:252-289), bench-report.md 존재 |
| R8-A4 | 조건부 충족 | 30/30 (100%)이나 fake LLM/임베딩 기준. 실모델 검증은 plan 13. 아래 설계 갭 5 |
| R9-A1 | 충족 | DemoRunner 3세션 시드 + 4질의 (DemoRunner.java:40-97), demo/run-demo.sh. fake 프로바이더로 재현 가능 |
| R9-A2 | 충족 | Q1이 17만 반환, Q2 inspect가 superseded 이력+출처 출력 (README 출력 예시와 일치) |
| R9-A3 | 충족 | README에 실행 방법/데모 출력/벤치 표 갱신됨 |
| N1 | 충족 | Flyway V3에 BEFORE UPDATE/DELETE 트리거 실재 (V3__utterance_immutable_trigger.sql), `n1` 테스트가 UPDATE/DELETE 예외를 실제 확인. UtteranceRepository는 `Repository` 인터페이스로 save/find만 노출 (UtteranceRepository.java:16-27). 단 TRUNCATE 경로 관찰 있음 - 아래 관찰 6 |
| N2 | 충족 | R3-A4로 검증 |
| N3 | 충족 | R3-A2로 검증 |
| N4 | 충족 | R2-A2, R7-A4로 검증 |
| N5 | 충족 | R6-A4로 검증 |

## 발견 결함 (수정함)

### 1. 추출 경로의 명시적 정정 미구현 (SPEC R4 우선순위 1순위 누락)

- SPEC R4: "명시적 정정(memory_correct 호출 **또는 추출이 정정 화행 감지**) → 대상 사실 retracted, 정정 사실 confidence 0.95, 정정 전파 트리거". 우선순위 규칙도 "명시적 정정 > 최신 세션 > 최신 발화".
- 실태: `ExtractionService`가 `llm.isCorrection()`을 호출해 `FactCandidate.explicitCorrection`에 담아 넘기지만, `DefaultConflictResolver.handleNewFact`가 이 플래그를 한 번도 읽지 않았다. 정정 화행이 일반 모순으로 처리되어 (a) 대상이 retracted가 아닌 superseded가 되고, (b) 정정 사실 confidence가 0.95가 아닌 0.9/0.6이 되고, (c) 정정이 더 오래된 세션에서 오면 최신 세션 규칙에 밀려 정정 자체가 superseded 되는 우선순위 위반.
- 수정: `DefaultConflictResolver.resolveContradiction`에 explicitCorrection 분기 추가 - 대상 RETRACTED(superseded_by=정정), 정정 confidence 0.95, SUPERSEDES 관계, 전파 트리거 (DefaultConflictResolver.java:135-146). `CorrectionService.correct`(memory_correct 경로)와 동일한 의미론.
- 테스트 추가: `r4_explicitCorrectionOutranksNewerSession`(정정이 더 오래된 세션이어도 승리 + retracted + 0.95), `r4_extractionDetectedCorrectionRetractsTarget`(추출 파이프라인 끝까지) (ConflictIntegrationTest.java).

### 2. memory_extract 결과 카운트 오류

- `ConflictResolver`가 DUPLICATE로 판정해 기존 사실을 confirm한 경우(임베딩 유사도 0.80~0.92 구간에서 발생)에도 `ExtractionService`가 무조건 `created++` 해서 요약이 틀렸다.
- 수정: `ConflictResolver.handleNewFact`가 `Resolution(fact, createdNew)`를 반환하도록 인터페이스 변경, `ExtractionService`가 createdNew에 따라 created/confirmed 분기 (ConflictResolver.java, ExtractionService.java:106-111, NoOpConflictResolver.java, McpToolService.java:131).

## 설계 수준 갭 (수정하지 않고 목록화)

1. **ReviewStep의 SUPERSEDE 판정이 superseded_by 없이 전이** (ReviewStep.java:85-90). LLM 판정이 "어느 활성 사실이 대체하는지"를 식별하지 않아 포인터를 못 채운다. superseded 의미론("더 새로운 사실로 대체됨")이 약해짐. 해소하려면 review 프롬프트가 대체 사실을 지목하도록 확장 필요.
2. **전파가 REFINES/DUPLICATES 간선만 순회** (DefaultPropagationService.java:78-80). SPEC은 "fact_relation 그래프를 깊이 2까지"라고만 명시. 구현은 CONTRADICTS/SUPERSEDES 간선을 해소 기록으로 보고 제외(승자가 즉시 needs_review 되는 역효과 방지 - 주석에 근거 문서화됨). R6-A1/A2는 충족. 합리적 판단이나 SPEC 문면과의 차이로 기록.
3. **type 필터가 벡터 topK(50) 이후에 적용** (MemorySearchService.java:70-77). 특정 type 검색 시 상위 50개 밖의 해당 type 사실이 누락될 수 있음. 정확성 위반은 아니고 recall 한계.
4. **R7-A2의 "MCP 클라이언트 왕복"이 in-process 검증** - McpServer.handle 직접 호출 테스트만 있고, MCP inspector 또는 Claude Code로의 실제 stdio 왕복 수동 확인 기록이 없다. plan 09의 "수동 왕복 확인 및 기록" 항목 미완. plan 13(실모델 검증)에서 함께 수행 권장.
5. **R8-A4가 fake 프로바이더 기준** - 30/30은 결정적 fake LLM/임베딩 결과. LLM 변동성이 흡수된 상태라 실모델 80%는 미검증. plan 13 대상.
6. **TRUNCATE 경로** - N1 트리거는 행 단위 UPDATE/DELETE만 차단하고 TRUNCATE는 통과한다. BenchHarness.resetDatabase(:233), DemoRunner(:37), 테스트 cleanup이 의도적으로 utterance를 TRUNCATE한다. 서버의 서비스/MCP 계층에는 utterance 변경 경로가 없으므로 N1 취지는 유지되나, 하니스가 같은 JVM/DB 권한으로 로그를 지울 수 있다는 점은 기록해 둔다.
7. **유사도 임계값이 정규화 스케일에 적용** - SPEC의 0.80/0.92 임계는 구현에서 (1+cos)/2 정규화 유사도에 적용된다 (원시 코사인 기준 0.60/0.84에 해당). 내부적으로 일관되고 벤치 통과하므로 동작 문제는 없으나, 실 임베딩 모델로 튜닝할 때(plan 13) 이 스케일 차이를 인지해야 한다.

## p95 실측 (R3-A5)

- `SearchIntegrationTest.r3a5_searchP95Under500msAt10kFacts`: 10,000건 fake 임베딩 시드 → 워밍업 5회 → 30회 측정 p95.
- 측정은 쿼리 임베딩(fake, in-process)을 포함하지만 외부 API 호출은 없음 - SPEC "외부 임베딩 API 호출 제외" 조건 만족.
- 이번 실행 실측값: p95 = 22.5ms (기준 500ms 미만, 여유 있게 통과)

## 회귀 확인

- 전체 스위트: 41 tests, 0 skipped, 0 failures (신규 테스트 2건 포함, 외부 DB 모드로 통합 테스트 전부 실행)
- 벤치 재실행 (수정 반영 후, fake 프로바이더): 30/30 (100%), bench-report.md 갱신

## plan 13 (실모델 검증) 착수 조건

- 필요 환경변수:
  - `MEMSTORE_LLM_PROVIDER=claude`, `ANTHROPIC_API_KEY`, `MEMSTORE_LLM_MODEL`(기본 claude-opus-4-8, application.yml)
  - `EMBEDDING_PROVIDER=openai`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`(예: text-embedding-3-small), `EMBEDDING_DIM=1536`
  - DB: `docker compose up -d` (5432 충돌 시 포트 변경 필요 - 이 머신은 cdc-view-engine이 5432 점유)
- 실행 항목:
  1. `./gradlew benchRun` 실모델 실행 → bench-report.md 갱신, 전체·모순 카테고리 ≥ 80% 확인 (R8-A4 확정)
  2. `bash demo/run-demo.sh` 실모델 데모 → README 출력 예시 갱신 (R9)
  3. MCP inspector 또는 `claude mcp add`로 stdio 실왕복 확인 → 설계 갭 4 해소 (R7-A2 확정)
  4. ClaudeLlmClient는 "unverified in this environment" 주석 상태 - 실호출 검증 필요. Opus 4.7/4.8이 temperature 파라미터를 거부해 미전송인 점(SPEC "temperature 0"과의 차이)도 이때 확인
  5. 정규화 유사도 임계(설계 갭 7)가 실 임베딩 분포에서 적절한지 확인, 필요 시 튜닝
