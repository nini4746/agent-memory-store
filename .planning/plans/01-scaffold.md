# Plan 01: 프로젝트 스캐폴드와 로컬 실행 환경

## 목표
Java 21 + Spring Boot 3.x 프로젝트 골격과 PostgreSQL 16 + pgvector 로컬 환경을 만든다. SPEC 요구사항의 직접 수용 기준은 없고, 이후 모든 plan의 실행 기반이다.

## 컨텍스트
- 프로젝트 루트: /Users/yunho/project/agent-memory-store (현재 README.md만 존재)
- 명세: .planning/SPEC.md - 스택 제약 섹션 참고 (Java 21, Spring Boot 3.x, pgvector, Flyway, docker-compose)
- 1인 개발, 로컬 실행 전제. CI는 단위 테스트만 돌릴 수 있으면 충분(외부 API 키 불필요)

## 작업
1. Gradle(권장) 또는 Maven으로 Spring Boot 3.x 프로젝트 생성 - 패키지 `dev.nini.memstore` 등 단일 모듈
2. 의존성: spring-boot-starter-web, spring-boot-starter-data-jpa, Flyway, PostgreSQL 드라이버, 테스트(JUnit 5, Testcontainers)
3. `docker-compose.yml` - pgvector 확장 포함 Postgres 16 이미지(예: pgvector/pgvector:pg16), 볼륨, 포트
4. `application.yml` - DB 접속, 프로파일(local/test), 환경변수 자리(LLM 키, 임베딩 설정 - 값은 비움)
5. Flyway 초기 마이그레이션 V1 - `CREATE EXTENSION IF NOT EXISTS vector`만
6. 헬스체크 엔드포인트(actuator) 활성화
7. `.gitignore`, git 저장소 초기화(없으면), 초기 커밋

## must-haves
- Testcontainers 기반 통합 테스트 골격 1개 (컨텍스트 로드 + Flyway 적용 확인)
- 외부 API 키 없이 `./gradlew test` 통과

## 검증
- [ ] `docker compose up -d` 후 Postgres에 vector 확장이 설치되어 있다 (`SELECT extname FROM pg_extension`)
- [ ] `./gradlew bootRun`으로 서버가 뜨고 `/actuator/health`가 UP을 반환한다
- [ ] `./gradlew test`가 키 없이 통과한다
