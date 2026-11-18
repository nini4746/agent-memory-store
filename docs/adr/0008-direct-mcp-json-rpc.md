# 0008. MCP JSON-RPC 직접 구현 (stdio)

- 상태: 채택
- 날짜: 2026-07-07

## 맥락
소비자는 AI 에이전트이므로 메모리 기능을 MCP 툴로 노출해야 합니다(R7). SPEC은 Spring AI MCP server starter를 우선으로 하되, 불가 시 JSON-RPC 직접 구현을 허용했습니다.

## 결정
- MCP JSON-RPC 2.0을 직접 구현합니다. 프로토콜 버전은 `2024-11-05`입니다.
- `McpServer.handle`은 요청 노드를 응답 노드로 바꾸는 전송 무관 핸들러이고, `McpStdioServer`가 stdin/stdout으로 구동합니다(`-Dmemstore.mcp.stdio=true`).
- 7개 툴: `memory_log_append`, `memory_extract`, `memory_search`, `memory_save_fact`, `memory_correct`, `memory_forget`, `memory_inspect`. 실제 로직은 `McpToolService`가 서비스 계층에 위임합니다.

## 대안
- Spring AI MCP server starter: BOM/버전 결합이 무거워 쓰지 않았습니다(Plan 09 커밋 기록).
- 웹 UI/대시보드: 소비자가 에이전트이고 inspect 툴로 충분해 범위 외입니다.

## 결과
- 장점: 전송과 분리되어 있어 in-process로 툴 왕복을 테스트합니다. 벤치 하니스도 MCP 툴 경유로 시드/질의합니다.
- 장점: 의존성이 가볍습니다.
- 단점/감수한 점: 외부 MCP 클라이언트(inspector, Claude Code)와의 실왕복은 테스트로 자동화되어 있지 않습니다.
