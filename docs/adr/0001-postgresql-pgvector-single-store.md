# 0001. Java/Spring 서버와 PostgreSQL + pgvector 단일 저장소

- 상태: 채택
- 날짜: 2026-07-07

## 맥락
원문 로그, 사실, 출처 링크, 관계 그래프, 임베딩 벡터를 모두 저장하고 벡터 유사도 검색까지 해야 합니다. 로컬 단일 사용자 실행이 전제이고, 1인 개발입니다.

## 결정
- Java 21 + Spring Boot 3.3 서버 하나로 만듭니다.
- 모든 상태를 PostgreSQL 16 + pgvector 한 곳에 저장합니다. 로컬 실행은 docker-compose로 띄웁니다.
- 스키마는 Flyway(`V1~V4`)가 관리하고, Hibernate는 스키마를 건드리지 않습니다(`ddl-auto: none`).
- 벡터 검색은 `fact.embedding`에 HNSW 인덱스(`vector_cosine_ops`, `V4__fact_embedding_index.sql`)를 두고 `<=>` 연산자로 합니다.
- embedding 컬럼은 JPA에 매핑하지 않고 네이티브 SQL `CAST(? AS vector)`로 기록합니다.

## 대안
- 임베디드(Rust/Go) 변형: 스택은 Java/Spring 서버형으로 확정했습니다(SPEC 범위 외).
- embedding 컬럼 JPA 매핑: pgvector 타입을 JPA에 매핑하지 않기 위해 네이티브 SQL을 씁니다.
- 분산/샤딩/고가용성: 단일 Postgres 전제로 범위 외입니다.

## 결과
- 장점: 관계형 데이터와 벡터가 한 DB에 있어 트랜잭션과 제약(트리거, provenance)을 같은 곳에서 강제합니다.
- 장점: 사실 10,000건에서 검색 내부 처리 p95가 기준 500ms를 통과했습니다(README 기준 22.5ms).
- 단점/감수한 점: 테스트에 실제 PostgreSQL이 필요합니다(Testcontainers 또는 `memstore.test.jdbcUrl` 외부 DB).
