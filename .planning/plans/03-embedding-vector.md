# Plan 03: 임베딩 프로바이더 추상화와 pgvector 유사도 검색 기반

## 목표
임베딩 프로바이더 인터페이스와 pgvector 코사인 유사도 조회를 만든다. SPEC의 제약 섹션(임베딩 추상화, 결정적 fake, embedding_model 혼합 거부)을 구현하는 기반 plan이며, R3/R4가 이 위에 올라간다.

## 컨텍스트
- 선행: Plan 02 (fact 테이블에 embedding vector, embedding_model 컬럼 존재)
- 명세: .planning/SPEC.md 제약 섹션, 엣지 해소 기록의 "임베딩 차원 불일치" 항목
- 방침: 기본 프로바이더는 환경변수로 지정(예: OpenAI text-embedding-3-small, 1536차원). 테스트는 외부 호출 없는 결정적 fake

## 작업
1. `EmbeddingProvider` 인터페이스 - `embed(text) -> float[]`, `modelId()`, `dimension()`
2. 구현 2종:
   - HTTP 기반 실 프로바이더 1종 (환경변수 EMBEDDING_API_KEY, EMBEDDING_MODEL, EMBEDDING_DIM)
   - `FakeEmbeddingProvider` - 텍스트 해시 기반 결정적 벡터, 유사 텍스트가 유사 벡터가 되도록 토큰 단위 해시 합산 방식
3. fact 저장 시 embedding_model 기록. 검색/비교 시 현재 프로바이더 modelId와 다른 fact는 후보에서 제외하고, 신규 저장 시 DB에 다른 modelId가 존재하면 경고 로그 (혼합 거부는 조회 필터로 구현)
4. pgvector 조회: `FactVectorSearch.topK(queryEmbedding, k, statusFilter)` - 코사인 거리 연산자(`<=>`) 사용, [0,1] 정규화 유사도 반환
5. 벡터 인덱스(HNSW 또는 IVFFlat) 마이그레이션 추가
6. 통합 테스트 - fake 프로바이더로 시드 후 topK 순서 검증

## must-haves
- 테스트 전체가 외부 API 키 없이 통과 (fake 주입)
- 유사도가 [0,1] 정규화되어 상위 계층(R3 점수식)에 전달됨

## 검증
- [ ] fake 임베딩으로 "비슷한 문장"이 "무관한 문장"보다 높은 유사도를 갖는 통합 테스트 통과
- [ ] embedding_model이 다른 fact가 topK 후보에서 제외된다 (테스트)
- [ ] 벡터 인덱스가 마이그레이션으로 생성된다
- 테스트 명령: `./gradlew test --tests '*Embedding*' --tests '*VectorSearch*'`
