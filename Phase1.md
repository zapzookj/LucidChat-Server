# [Phase 1] Lucid Chat - MVP 구축 완료 보고서

## 1. 프로젝트 개요
- **프로젝트명:** Lucid Chat (구 AI Character Chat)
- **목표:** "살아있는 캐릭터와 대화하는 경험"을 제공하는 웹 기반 AI 미연시 서비스
- **개발 단계:** MVP (Minimum Viable Product) 구축 완료

## 2. 기술 스택 (Tech Stack)
- **Backend:**
    - Language: Java 17
    - Framework: Spring Boot 3.x
    - Database: MariaDB (JPA/Hibernate)
    - Security: Spring Security + JWT (Access Token 위주)
    - Auth: 자체 회원가입 + Google OAuth2
    - AI Integration: OpenRouter API (OpenAI/Anthropic 모델 연동)

- **Frontend:**
    - Framework: React + Vite
    - Style: Tailwind CSS (Glassmorphism 디자인 시스템 적용)
    - Animation: Framer Motion (캐릭터 숨쉬기, UI 전환)
    - HTTP Client: Axios

## 3. 핵심 구현 기능 (MVP 기준)
1. **인증 시스템 (Authentication):**
    - 이메일/비밀번호 회원가입 및 로그인.
    - Google 소셜 로그인 연동.
    - JWT 기반의 Stateless 인증 구현.

2. **채팅 시스템 (Chat Engine):**
    - `ChatRoom`: 유저와 캐릭터 간 1:1 채팅방 생성.
    - `ChatLog`: 대화 내역 DB 저장 및 불러오기 (Pagination).
    - 캐릭터의 감정(Emotion)에 따라 프론트엔드 이미지 동적 변경.
    - 기본적인 호감도(Affection) 점수 시스템 (0~100).

3. **UI/UX (Glassmorphism):**
    - 배경이 비치는 반투명한 대화창 및 UI 컴포넌트 구현.
    - 캐릭터 Idle 애니메이션 (둥둥 떠다니는 효과, 숨쉬기).
    - 반응형 레이아웃 (모바일/데스크탑 대응 기초).

## 4. 데이터베이스 스키마 (핵심 엔티티)
- **Users:** `id`, `email`, `nickname`, `provider`, `energy`
- **Characters:** `id`, `name`, `personality`, `tone`, `default_image_url`
- **ChatRooms:** `id`, `user_id`, `character_id`, `affection_score`, `status_level`
- **ChatLogs:** `id`, `room_id`, `role` (USER/ASSISTANT), `raw_content`, `clean_content`, `emotion_tag`

## 5. Phase 1의 한계점 (Phase 2에서 개선됨)
- 단조로운 텍스트 출력 (스트리밍 없음).
- 괄호 파싱 로직의 단순함 (텍스트 전처리 방식).
- 리프레시 토큰 부재로 인한 로그인 풀림 현상.
- 정적인 배경 이미지.