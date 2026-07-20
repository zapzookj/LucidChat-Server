# [Phase 3] Lucid Chat - 지능(Brain) 및 백엔드 성능 최적화 완료 보고서

## 1. 프로젝트 개요
- **프로젝트명:** Lucid Chat (AI 미연시 & 비주얼 노벨 플랫폼)
- **개발 단계:** Phase 3 (The Brain) 완료
- **핵심 목표:** AI 캐릭터에 **장기 기억(Long-term Memory)**을 이식하고, 백엔드 구조 개선을 통해 **응답 속도(Latency)**를 획기적으로 단축한다.

## 2. 기술 스택 변경 사항 (Tech Stack Updates)
- **Database & Storage:**
    - **Vector DB:** Pinecone (Serverless) - 장기 기억 임베딩 저장 및 검색.
    - **In-Memory:** Redis - 고빈도 조회 데이터 캐싱 (User, Character, Auth).
- **AI Model:**
    - **Embedding:** `text-embedding-3-small` (OpenRouter/OpenAI).
- **Backend Core:**
    - **Profiling:** Spring `StopWatch`를 활용한 정밀 레이턴시 측정.
    - **Async Processing:** `@Async` 및 트랜잭션 분리를 통한 Non-blocking 최적화.
    - ~~**Reactive Stack:** Spring WebFlux (SSE 구현 시도 후 롤백).~~

## 3. 핵심 구현 기능 (Phase 3 성과)

### A. RAG 기반 장기 기억 (Long-term Memory)
캐릭터가 유저와의 과거 대화 내용, 설정, 중요 사건을 영구적으로 기억합니다.
1.  **Pinecone Integration:**
    - 대화 내용을 임베딩하여 Vector DB에 저장 및 유사도 검색(Search) 구현.
    - **Connection Caching:** 매 호출마다 발생하는 핸드셰이크 오버헤드를 제거하여 Pinecone 연결 시간 단축 (5~6s → 1.5s).
2.  **Smart RAG Skip:**
    - 대화가 충분히 쌓이지 않은 초기 단계(20턴 미만)에서는 RAG 파이프라인을 통째로 스킵하여 응답 속도 확보.
3.  **Periodic Summarization (Memory Consolidation):**
    - 매 20턴마다, 혹은 세션 종료 시 백그라운드에서 대화 내용을 요약하여 핵심 기억만 저장.
    - **Context Injection:** 검색된 기억을 System Prompt에 동적으로 주입.

### B. 백엔드 성능 최적화 (Bottleneck Breaker)
체감 속도가 아닌, 서버의 **물리적 처리 속도**를 개선했습니다.
1.  **Transaction Separation (트랜잭션 분리):**
    - 기존: `sendMessage` 전체가 하나의 트랜잭션 (LLM 대기 시간 동안 DB 커넥션 점유).
    - 개선: **[전처리(User저장)] - [LLM호출(No-TX)] - [후처리(AI저장)]** 로 분리.
    - 효과: DB 커넥션 점유 시간 최소화 및 동시 접속 처리량 증대.
2.  **Latency Profiling & Tuning:**
    - `StopWatch`를 통해 구간별(RAG, Prompt, LLM, DB) 소요 시간 측정.
    - 전체 응답 대기 시간을 기존 대비 약 **50% 단축**.
3.  **Concurrency Handling:**
    - 메모리 요약 시점과 채팅 응답 시점이 겹칠 때 발생하는 OpenRouter 동시 요청 에러 해결 (Retry 로직 추가).

### C. Redis Caching 전략
반복적이고 변동이 적은 데이터 조회를 캐싱하여 DB 부하를 제로(0)에 가깝게 줄였습니다.
- **AuthGuard:** 방 소유권 검증 로직 영구 캐싱 (가장 빈번한 호출).
- **Static Data:** 캐릭터 페르소나/설정 데이터 영구 캐싱.
- **Dynamic Data:** User 프로필(30분), ChatRoom 정보(60초) 캐싱 및 Eviction(갱신 시 삭제) 구현.

### D. ~~Real-time Streaming (SSE) - ROLLED BACK~~
- **시도 내용:** `WebFlux`를 도입하여 타자기 효과처럼 글자가 생성되는 대로 실시간 전송 시도.
- **롤백 사유 (Post-Mortem):**
    - **Bottleneck Misdiagnosis:** 프로파일링 결과, 서버->클라이언트 전송 구간(Network)은 병목이 아니었음.
    - **LLM Dependency:** 주요 지연은 LLM의 첫 토큰 생성(TTFT)과 생성 속도 자체에 있었음.
    - **Cost/Benefit:** SSE 도입으로 인한 코드 복잡도 증가 대비, 체감되는 속도 개선 효과가 미미함(약 0.2~0.5초 차이).
    - **결론:** SSE를 걷어내고, **RAG 최적화와 트랜잭션 분리**를 통해 전체 응답 시간을 줄이는 **정공법**을 택함.

## 4. 향후 계획 (Next Step: Phase 4)
- **주제:** **The Soul (몰입감 및 콘텐츠 확장)**
- **Dynamic Background:** 대화 문맥(시간, 장소)에 따른 배경 자동 전환.
- **Advanced Events:** 관계 단계 승급(썸→연인), 멀티 엔딩 시스템.
- **Multi-Character:** 캐릭터 데이터 구조 확장을 통한 히로인 추가.