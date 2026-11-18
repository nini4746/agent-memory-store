# 0003. LLM/임베딩 프로바이더 추상화와 fake 기본값

- 상태: 채택
- 날짜: 2026-07-07

## 맥락
추출, 충돌 분류, 재검토는 LLM이, 검색과 중복/충돌 후보 탐색은 임베딩이 필요합니다. 테스트, 데모, 벤치는 외부 API 키 없이 결정적으로 돌아가야 합니다(SPEC 제약). 임베딩 모델이 바뀌면 벡터 차원과 공간이 달라집니다.

## 결정
- `LlmClient`(`FakeLlmClient`, `ClaudeLlmClient`)와 `EmbeddingProvider`(`FakeEmbeddingProvider`, `OpenAiEmbeddingProvider`) 인터페이스를 둡니다.
- 기본값은 둘 다 fake입니다(`MEMSTORE_LLM_PROVIDER:fake`, `EMBEDDING_PROVIDER:fake`). 실제 프로바이더는 환경변수로 켭니다.
- fact마다 `embedding_model`을 기록하고, 벡터 검색은 `embedding_model` 일치 필터로 다른 모델 벡터끼리 비교하지 않습니다.
- Claude 요청에는 temperature/top_p를 보내지 않습니다. 사용 모델(Opus 4.7/4.8)이 sampling 파라미터를 거부하기 때문입니다. 결정성은 고정 프롬프트에 의존합니다.

## 대안
- 실제 API를 기본값으로: 키 없이 테스트, 데모, 벤치를 돌릴 수 없습니다.
- 모델 교체 시 재임베딩 마이그레이션: 범위 외입니다. 혼합을 거부하는 것까지만 보장합니다.
- Claude 요청에 temperature 0: SPEC은 temperature 0을 적었지만, 모델이 거부해 보내지 않습니다.

## 결과
- 장점: 전체 테스트와 벤치(30/30)가 외부 의존 없이 결정적으로 돌아갑니다.
- 단점/감수한 점: 벤치 결과는 fake 프로바이더 기준입니다. 실모델 정답률은 검증되지 않았고, `ClaudeLlmClient`는 이 환경에서 미검증입니다.
- 단점/감수한 점: 임베딩 모델을 바꾸면 기존 사실은 새 모델 검색에서 보이지 않습니다.
