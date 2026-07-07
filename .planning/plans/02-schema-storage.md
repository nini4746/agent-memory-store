# Plan 02: 스키마와 저장 모듈 - 무손실 로그와 fact 저장

## 목표
SPEC R1(표현/저장)을 구현한다 - session/utterance/fact/fact_provenance/fact_relation 스키마와 저장/조회 서비스, 원문 로그 불변 보장(N1).

## 컨텍스트
- 선행: Plan 01 (Spring Boot 골격, Flyway, Testcontainers)
- 명세: .planning/SPEC.md "용어와 핵심 스키마" 섹션이 스키마 원본
- 스키마 요약:
  - session(id uuid, source text, started_at, ended_at, metadata jsonb)
  - utterance(id uuid, session_id fk, seq int, role text, content text, created_at) unique(session_id, seq)
  - fact(id uuid, statement text, type enum: preference|constraint|decision|event|entity, embedding vector(차원 설정값), embedding_model text, confidence float, status enum: active|superseded|retracted|expired, needs_review bool default false, created_at, last_confirmed_at, invalidated_at, superseded_by uuid nullable)
  - fact_provenance(fact_id, utterance_id) 복합 PK
  - fact_relation(from_fact_id, to_fact_id, relation enum: contradicts|supersedes|refines|duplicates, created_at)

## 작업
1. Flyway V2 마이그레이션 - 위 5개 테이블 + 인덱스(utterance(session_id, seq), fact(status), fact_provenance 양방향)
2. Flyway V3 - utterance UPDATE/DELETE를 차단하는 DB 트리거 (RAISE EXCEPTION)
3. JPA 엔티티 + 리포지토리. utterance 리포지토리에는 수정/삭제 메서드를 노출하지 않음
4. 저장 서비스:
   - `SessionLogService.append(sessionId?, source, entries[])` - 세션 없으면 생성, seq 자동 증가, 원문 그대로 저장
   - `FactStore.save(fact, provenanceUtteranceIds)` - provenance 0개면 예외
   - `FactStore.transition(factId, newStatus, supersededBy?)` - 상태 전이 + invalidated_at 기록
   - fact → 출처 utterance 역참조 조회
5. 통합 테스트 (Testcontainers)

## must-haves
- provenance 없는 fact 저장이 서비스 계층에서 예외로 거부됨
- 상태 전이 시 invalidated_at, superseded_by가 채워짐

## 검증
- [ ] R1-A1 세션 로그 append 후 원문이 바이트 동일하게 재조회된다 (특수문자/개행/이모지 포함 케이스)
- [ ] R1-A2 fact 상태 전이 연산을 수행해도 utterance 행 수가 감소하지 않는다 (통합 테스트)
- [ ] R1-A3 fact의 status, confidence, last_confirmed_at, superseded_by가 저장되고 상태 전이가 기록된다
- [ ] R1-A4 fact_provenance로 fact → 출처 utterance 역참조 조회가 동작한다
- [ ] N1 utterance에 대한 SQL UPDATE/DELETE가 트리거로 거부된다 (테스트에서 예외 확인)
- 테스트 명령: `./gradlew test --tests '*Storage*'`
