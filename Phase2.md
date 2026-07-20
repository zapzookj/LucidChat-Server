📦 Lucid Chat 프로젝트 이관 명세서 (Phase 2 → Phase 3)

1. 프로젝트 개요
   서비스명: Lucid Chat (AI 캐릭터 미연시 채팅 & 비주얼 노벨)

핵심 가치: 단순 채팅을 넘어선 '서사(Narrative)'와 '연출'이 있는 몰입형 경험 제공.

현재 상태: MVP 런칭 후 고도화 단계 (Phase 2 완료)

2. 기술 스택 (Tech Stack)
   Backend:

Java 17, Spring Boot (4.x M1)

Spring Security (OAuth2 + JWT), JPA

Redis (Refresh Token 관리, 로그아웃 블랙리스트)

OpenRouter API (LLM 연동)

Frontend:

React 19, Vite, TailwindCSS, Framer Motion, Axios

Lucide-react (Icons)

3. 현재까지 구현된 핵심 기능 (Phase 1 & 2 완료)
   보안 & 인증:

JWT Access/Refresh Token 구조 (Refresh Token은 HttpOnly Cookie 저장).

Redis 기반 토큰 관리 및 로그아웃(Blacklist).

@PreAuthorize 기반의 철저한 API 권한 검증 (AuthGuard).

OAuth2 (Google) 로그인 및 자동 회원가입/연동.

채팅 엔진:

ChatRole: USER, ASSISTANT, SYSTEM(나레이터) 구조.

나레이션 엔진 (Narrative Engine): NarratorService를 통해 상황 연출 이벤트 생성.

캐릭터 자동 반응: 이벤트 발생 시 캐릭터가 상황을 인지하고 먼저 말을 거는 선톡 기능 구현.

UI/UX (Frontend):

Visual Novel Style: 대화 로그가 아닌 '컷신' 형태의 대사 출력 (DialogueBox).

연출 강화: 타자기 효과(Typewriter), 호감도/에너지 게이지 및 팝업 애니메이션, 이벤트 발생 시 테마 변경(보라색/중앙 정렬).

편의 기능: BGM 플레이어, 설정 모달(유저 프로필 수정), 히스토리 모달.

4. 주요 코드 구조 (Context Retention)
   Backend:

StoryController: 이벤트 트리거 API (POST /rooms/{id}/events).

NarratorPromptAssembler: 전지적 작가 시점 프롬프트 생성기.

ChatService: SYSTEM 메시지를 컨텍스트에 포함하여 LLM에 전달.

Frontend:

ChatPage.jsx: handleTriggerEvent와 handleNextScene을 분리하여 이벤트 연출 후 캐릭터 반응을 유도하는 로직.

DialogueBox.jsx: isEventScene 플래그에 따라 일반 대화/이벤트 연출을 분기 처리.

5. Phase 3 목표 (Next Steps)
   장기 기억 (Long-term Memory): Vector DB(RAG) 도입으로 캐릭터 기억력 강화.

성능 최적화: Redis 캐싱, Kafka 비동기 큐 도입 (대용량 트래픽 대응).

백오피스 구축: Admin 페이지 및 로그 파이프라인.

수익화: 결제 모듈 연동.