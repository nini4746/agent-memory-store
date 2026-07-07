# Plan 11: 데모 시나리오와 README 갱신

## 목표
SPEC R9(데모)를 구현한다 - 3세션 데모 스크립트 1회 실행으로 충돌 해소/출처 추적/예산 패킹을 재현하고 README를 갱신한다.

## 컨텍스트
- 선행: Plan 09 (MCP 툴), Plan 10 (벤치 리포트 - README에 수치 인용)
- 명세: .planning/SPEC.md R9. 데모 시나리오:
  - 3월 세션: "PostgreSQL 15 사용 결정" + "AWS 배포" 발화
  - 5월 세션: "탭 대신 스페이스 선호" 발화
  - 6월 세션: "PostgreSQL 17로 마이그레이션" + 5월 선호에 대한 명시적 정정 발화
  - 질의 4개: (1) Postgres 버전 → 17만 반환, (2) memory_inspect로 15 fact의 superseded 이력 + 출처 발화, (3) 낮은 budget_tokens 검색으로 패킹 동작, (4) 정정된 선호 반영 결과
- 세션 시각은 상대 오프셋으로 시드 (하니스의 시계 훅 재사용 가능)
- 실행에 실제 LLM/임베딩 키 필요

## 작업
1. `demo/run-demo.sh` (또는 gradle task `demoRun`) - 서버 기동 전제, MCP 툴 호출로 3세션 시드 → extract → 질의 4개 → 결과를 사람이 읽기 좋게 출력
2. 각 질의 출력에 라벨 - 무엇을 보여주는 단계인지 (충돌 해소 / 출처 / 예산 / 정정)
3. README.md 갱신:
   - 실행 방법 (docker compose, 환경변수, 서버 기동, MCP 등록, 데모 실행)
   - 데모 출력 예시 (실제 실행 결과 붙여넣기)
   - 벤치마크 결과 요약 (bench-report.md의 M/N 표 인용)
   - 아키텍처 한 단락 (4모듈 구조와 유지보수 차별화)
4. 데모 실행 및 출력 검수

## must-haves
- 데모가 깨끗한 DB에서 시작 (스크립트가 초기화 포함 또는 초기화 안내)
- README만 보고 제3자가 데모를 재현 가능

## 검증
- [ ] R9-A1 데모 스크립트 1회 실행으로 3세션 시드와 4개 질의 결과가 출력된다
- [ ] R9-A2 "Postgres 버전" 질의에 17만 반환되고, inspect로 15의 superseded 이력과 출처 발화가 확인된다
- [ ] R9-A3 README에 실행 방법과 데모 출력 예시가 갱신된다
- 실행 명령: `bash demo/run-demo.sh` (또는 `./gradlew demoRun`)
