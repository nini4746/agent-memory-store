# Plan 09: MCP 서버 노출 - 7개 툴

## 목표
SPEC R7(MCP 인터페이스)을 구현한다 - 기존 서비스 계층을 7개 MCP 툴로 노출하고 MCP 클라이언트 왕복을 확인한다.

## 컨텍스트
- 선행: Plan 02~08 전부 (서비스 계층 완성 상태)
- 명세: .planning/SPEC.md R7. 툴 목록:
  1. memory_log_append(session_id?, source?, entries[]) → session_id - SessionLogService
  2. memory_extract(session_id) → 추출 요약 - ExtractionService
  3. memory_search(query, budget_tokens?, types?, include_inactive?) → 기억 묶음 + 출처 id - MemorySearchService
  4. memory_save_fact(statement, type) → fact_id - 합성 세션("direct" source) + 합성 발화를 만들어 provenance 보장 후 FactStore + ConflictResolver 경유
  5. memory_correct(fact_id, correction_statement) - CorrectionService (전파 포함)
  6. memory_forget(fact_id, reason) - retract 전이, 대체 없음, reason은 metadata 기록
  7. memory_inspect(fact_id) - 진술/상태/confidence/provenance(세션+발화 원문)/관계/supersede 이력
- 구현: Spring AI MCP server starter 우선 시도. 부적합하면 MCP JSON-RPC(stdio 또는 SSE) 직접 구현으로 전환 - 전환 시 사유를 plan 실행 기록에 남김
- 로컬 검증 클라이언트: `npx @modelcontextprotocol/inspector` 또는 Claude Code `claude mcp add`

## 작업
1. MCP 서버 의존성 추가 및 전송 방식 결정 (stdio 또는 SSE - Claude Code 연동 가능한 쪽)
2. 7개 툴 정의 - 이름, 설명, JSON 스키마(필수/선택 파라미터), 서비스 위임
3. memory_save_fact의 합성 provenance 경로 구현
4. 오류 매핑 - 존재하지 않는 fact_id, 잘못된 type 등을 MCP 오류 응답으로
5. 통합 테스트 - 툴 계층을 서비스 fake 없이 실제 스택(Testcontainers + fake LLM/임베딩)으로 호출
6. MCP inspector 또는 Claude Code로 수동 왕복 확인 및 기록

## must-haves
- 툴 설명문이 에이전트가 언제 뭘 호출할지 판단할 수 있게 작성됨 (memory_search와 memory_inspect의 용도 구분 등)
- provenance 없는 fact 생성 경로가 MCP 계층에도 없음

## 검증
- [ ] R7-A1 tools/list에 7개 툴과 파라미터 스키마가 노출된다
- [ ] R7-A2 MCP 클라이언트(Claude Code 또는 MCP inspector)에서 memory_search 왕복 호출이 성공한다
- [ ] R7-A3 memory_correct 호출이 충돌 해소와 전파 파이프라인을 실제로 트리거한다 (통합 테스트 - 대상 retracted + 이웃 needs_review 확인)
- [ ] R7-A4 memory_save_fact로 저장된 fact가 합성 provenance를 가지며, provenance 없는 fact는 생성 불가하다
- 테스트 명령: `./gradlew test --tests '*Mcp*'`
