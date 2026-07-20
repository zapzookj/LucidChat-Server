## 2. 문제 진단 및 해결 방향

### 기존 문제점
1. **캐릭터 엔진의 관성**: 시스템 프롬프트 최상단에 `You are [캐릭터]`로 자아가 고정되어 있어, 어떤 이벤트 나레이션을 주입해도 캐릭터가 결국 "유저를 찾는" 행동으로 수렴.
2. **감독 부재**: 이벤트 시스템이 있었지만 상황을 던져주기만 할 뿐, 시나리오의 흐름과 연출을 통제하는 주체가 없었음.
3. **1:1 핑퐁 고착**: 나레이터 엔진의 비중이 좁아서, 모든 대화가 "유저 → 캐릭터 → 유저" 구조에서 벗어나지 못함.

### 해결 방향
- **자유 모드:** 기존 유지 (액터만, 유저↔캐릭터 1:1 티키타카)
- **스토리 모드:** 디렉터 + 액터 투트랙 아키텍처 도입
    - 디렉터: 상황 묘사, 환경 변화, 시나리오 흐름 통제
    - 액터: 디렉터의 연기 지시(Stage Direction)에 따라 캐릭터 연기

---

## 3. 아키텍처 설계

### 3.1 전체 구조

```
              ┌──────────────────────────────┐
              │      Director Engine          │
              │  (비동기 선행 판단, 경량 LLM)    │
              │  sentimentModel (Gemini Flash) │
              └──────────┬───────────────────┘
                         │
            ┌────────────┼────────────────┐
            │            │                │
       INTERLUDE      BRANCH        TRANSITION
      (깜짝 개입)    (선택지 제시)    (시간/장소 전환)
            │            │                │
     topic=false    topic=true       topic=true
     대화 도중       씬 전환점         씬 마무리
            │            │                │
            └────────────┴────────────────┘
                         │
                   [유저에게 표시]
                  나레이션 → 인지 → 행동
                         │
                   [액터에게 전달]
              Directive + 유저 메시지 → 연기
```

### 3.2 핵심 설계 원칙

| 원칙 | 설명 |
|------|------|
| **비동기 선행 디렉팅** | 디렉터는 유저가 응답을 읽는 동안 백그라운드에서 판단 → 체감 레이턴시 0 |
| **유저 먼저 인지** | 디렉터 나레이션은 항상 유저에게 먼저 보여준 뒤 액터에게 전달 → 레이스 컨디션 원천 차단 |
| **단일 오케스트레이터** | 기존 이벤트 트리거 + 시간 넘기기를 디렉터 단일 두뇌로 통합 → 기능 충돌 방지 |
| **Stage Direction 패턴** | 액터에게 "감독의 연기 지시"를 명시적으로 전달 → 캐릭터가 유저를 찾는 관성 극복 |
| **Graceful Degradation** | 디렉터 실패 시 기존 순수 액터 모드로 자연스럽게 폴백 |

### 3.3 모드별 아키텍처 비교

| 항목 | 자유 모드 (Sandbox) | 스토리 모드 (Story) |
|------|-------------------|-------------------|
| LLM 구조 | 액터 단독 | 디렉터 + 액터 |
| 이벤트 | 없음 | 디렉터 자동 판단 |
| 시나리오 통제 | 없음 | 디렉터가 흐름 관리 |
| NPC | 없음 | 디렉터 지시로 등장 |
| 에너지 비용 | 1 | 2 (디렉터 비용 포함) |
 
---

## 4. 턴 플로우 (상세)

### 4.1 정상 플로우 (비동기 선행 디렉팅)

```
[유저 메시지 전송]
    │
    ▼
[액터 응답 생성 (SSE 스트리밍)]
    │
    ▼
[final_result 수신 → 프론트 렌더링 완료]
    │
    ├─ 비동기 후처리 (triggerPostProcessing)
    │   ├─ 메모리 요약 (기존)
    │   ├─ 캐릭터 생각 생성 (기존)
    │   └─ ★ directorService.evaluateAndCache() (디렉터 판단)
    │       │
    │       ├─ 가드레일 체크 (6종) → 대부분 여기서 return
    │       ├─ 최근 대화 요약 구성
    │       ├─ 디렉터 LLM 호출 (경량 모델)
    │       ├─ DirectorDirective 생성
    │       ├─ topic_concluded 기반 유형 필터링
    │       └─ Redis 캐시 (TTL 10분)
    │
    ├─ 프론트: scheduleDirectorAutoCheck() (4초 타이머)
    │
    ▼
[4초 후 — 자동 체크]
    ├─ peekDirectorDirective() → Directive 없음 → 아무 일 없음
    └─ peekDirectorDirective() → Directive 있음 → DirectorInterlude 자동 표시
```

### 4.2 디렉터 인터루드 플로우

```
[DirectorInterlude 표시 — 유저가 나레이션을 읽음]
    │
    ├─ INTERLUDE (깜짝 이벤트)
    │   ├─ "숨죽여 지켜보기" → consumeDirective → 이벤트 시작 (OBSERVER)
    │   │   → 캐릭터↔디렉터 티키타카 (유저는 관찰자)
    │   │   → 유저가 "끼어들기" 시 → RESOLVED
    │   └─ "상황에 반응하기" → consumeDirective → 입력창 활성화 (FREE)
    │       → 유저가 직접 타이핑 → 액터가 constraint 기반으로 응답
    │
    ├─ BRANCH (선택지)
    │   → 유저가 선택 → consumeDirective → sendDirectorBranchStream (SSE)
    │   → 이벤트 시작 → 기존 이벤트 선택 플로우와 동일
    │
    └─ TRANSITION (시간/장소 전환)
        → 유저가 "계속" → consumeDirective → sendDirectorTransitionStream (SSE)
        → 장소 전환 애니메이션 → 액터가 새 상황에서 응답
```

### 4.3 유저 수동 디렉터 호출 플로우

```
[유저가 "다음 씬" 버튼 클릭]
    │
    ▼
[POST /director/request — 동기 LLM 호출]
    │
    ├─ 1차 시도: 일반 프롬프트 → 결과 검증
    │   ├─ 유효 → Redis 캐시 + 반환
    │   └─ 무효 (타입 미스매치) → 2차 시도 (강화 프롬프트)
    │       ├─ 유효 → Redis 캐시 + 반환
    │       └─ 무효 → PASS 반환 ("적절한 타이밍이 아닌 것 같아요")
    │
    ▼
[프론트: DirectorInterlude 표시 → 이후 동일 플로우]
```
 
---

## 5. Directive 라이프사이클

```
[생성]                    [캐시]              [표시]              [소비]              [적용]
DirectorService      → Redis 저장      → 프론트 peek     → POST /consume     → ChatRoom에
 .evaluateAndCache()   TTL 10분           DirectorInterlude    Redis 삭제           constraint 세팅
 또는                                      표시                                    
 .requestManual()                                                               [사용]
                                                                                CharacterPromptAssembler
                                                                                 .buildDirectorInterludeBlock()
                                                                                 에서 자동 감지 → 액터 프롬프트 주입
 
                                                                                [클리어]
                                                                                TX-2에서 소비 후 클리어
                                                                                 - FREE: 즉시 클리어 (일회성)
                                                                                 - OBSERVER: RESOLVED 시 클리어
```
 
---

## 6. 가드레일 체계

### 6.1 LLM 호출 전 가드레일 (백엔드)

| # | 조건 | 동작 | 목적 |
|---|------|------|------|
| 1 | SANDBOX 모드 | 즉시 return | 자유 모드는 디렉터 비활성 |
| 2 | 이벤트 ONGOING | 즉시 return | 이벤트 중복 방지 |
| 3 | 승급 이벤트 진행/대기 중 | 즉시 return | 승급과 디렉터 충돌 방지 |
| 4 | 엔딩 도달 | 즉시 return | 엔딩 후 불필요 |
| 5 | 마지막 개입 후 3턴 미만 | 즉시 return | 과도한 개입 방지 |
| 6 | 캐시된 Directive 미소비 | 즉시 return | 중복 생성 방지 |

### 6.2 타이밍 가드레일 (LLM 출력 검증)

| topic_concluded | 허용 유형 | 금지 유형 | 근거 |
|----------------|----------|----------|------|
| false (대화 중) | INTERLUDE | BRANCH, TRANSITION | 대화 중간에 선택지/전환은 부자연 |
| true (대화 끝) | BRANCH, TRANSITION | INTERLUDE | 끝난 대화에 깜짝 이벤트는 부자연 |

### 6.3 수동 요청 시 재시도 메커니즘

```
1차 시도: 일반 프롬프트 (허용 타입 명시)
    ├─ 유효 → 사용
    └─ 무효 → 2차 시도 (강화 프롬프트 — FORBIDDEN 3회 반복)
        ├─ 유효 → 사용
        └─ 무효 → PASS 반환 (LLM이 지시를 2회 연속 무시한 경우)
```
 
---

## 7. CharacterPromptAssembler 개선 — Stage Direction 패턴

### 기존 문제

```
기존: [이벤트 시작] → "이벤트 중이다, 유저가 지켜보고 있다"
     → 캐릭터: "주인님 어디 계세요?" (유저를 찾는 관성 발동)
```

### 개선 후

```
개선: [이벤트 시작] → "감독이 너에게 이 상황에서 이렇게 연기하라고 지시했다"
     → 캐릭터: (감독 지시에 따라 소리가 난 방향을 경계) (유저를 찾지 않음)
```

### 핵심 프롬프트 구조

```
# 🎬 DIRECTOR'S STAGE DIRECTION — Priority: HIGHEST
 
## 📖 SCENE SETUP (유저가 이미 읽은 상황):
"어두운 복도 끝에서 무언가 부딪히는 소리가 들려온다..."
 
## 🎭 YOUR ACTING INSTRUCTIONS (⚠️ FOLLOW EXACTLY):
캐릭터는 소리에 놀라 몸을 움츠린다. 유저를 찾지 않고, 소리가 난 방향을 경계한다.
 
## ⚠️ ABSOLUTE RULES:
1. FOLLOW the acting instructions above.
2. React to the SITUATION, not to the user.
```
 
---

## 8. 파일 구조 및 변경 사항

### 신규 파일 (5개)

| 파일 | 패키지 | 역할 |
|------|--------|------|
| `DirectorDirective.java` | dto/director | 디렉터 판단 결과 DTO — PASS/INTERLUDE/BRANCH/TRANSITION |
| `DirectorPromptAssembler.java` | service/prompt | 디렉터 LLM 시스템 프롬프트 조립 + 지켜보기 전용 프롬프트 |
| `DirectorService.java` | service/director | 핵심 엔진 — 비동기 판단, Redis 캐시, 소비, 수동 호출, 가드레일 |
| `DirectorInterlude.jsx` | components | 인터루드 UI — 나레이션 타자기 효과, 유형별 행동 버튼 |
| `StoryController.java` | controller | 디렉터 API 엔드포인트 5개 + 기존 레거시 호환 |

### 수정 파일 (7개)

| 파일 | 변경 내용 |
|------|-----------|
| `ChatRoom.java` | 디렉터 상태 필드 3개 + 메서드 5개 추가 |
| `ChatModePolicy.java` | 디렉터 기능 플래그 2개 + 상수 추가 |
| `CharacterPromptAssembler.java` | Stage Direction 프롬프트 블록, NPC 블록 조건 완화 |
| `ChatStreamService.java` | triggerPostProcessing에 디렉터 호출, applyDirectiveToRoom, TX-2 클리어 로직, sendDirectorWatchStream 프롬프트 강화 |
| `UseChatStream.js` | 디렉터 API 함수 5개 추가 |
| `DialogueBox.jsx` | "다음 씬" 버튼 (기존 이벤트 트리거 + 시간 넘기기 통합) |
| `ChatPage.jsx` | 디렉터 상태 4개, checkDirectorInterlude, handleDirectorInterludeComplete, handleRequestDirector, scheduleDirectorAutoCheck, DirectorInterlude 렌더링 |

### DB 마이그레이션

```sql
ALTER TABLE chat_rooms ADD COLUMN last_director_turn BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_rooms ADD COLUMN active_director_constraint TEXT;
ALTER TABLE chat_rooms ADD COLUMN active_director_narration TEXT;
```
 
---

## 9. API 엔드포인트

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/api/v1/story/rooms/{roomId}/director/peek` | Directive 확인 (소비 안 함) | JWT + RoomOwner |
| POST | `/api/v1/story/rooms/{roomId}/director/consume` | Directive 소비 + ChatRoom 적용 | JWT + RoomOwner |
| POST | `/api/v1/story/rooms/{roomId}/director/request` | 유저 수동 디렉터 호출 (동기) | JWT + RoomOwner + RateLimit |
| POST | `/api/v1/story/rooms/{roomId}/director/apply-branch` | BRANCH 선택 → SSE | JWT + RoomOwner + RateLimit |
| POST | `/api/v1/story/rooms/{roomId}/director/apply-transition` | TRANSITION → SSE | JWT + RoomOwner + RateLimit |
 
---

## 10. 비용 및 성능 영향

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| LLM 호출/턴 (스토리) | 1.0회 | 1.0~1.3회 (평균) |
| 디렉터 LLM 실호출 빈도 | - | 4~6턴에 1회 (가드레일 필터링) |
| 디렉터 모델 | - | sentimentModel (Gemini Flash 급, 저비용) |
| 체감 레이턴시 증가 | - | 0ms (비동기 후처리) |
| Redis 추가 사용량 | - | Directive 1개 수백 바이트, TTL 10분 |
| 수동 요청 시 대기 | - | 3~8초 (경량 모델 1~2회 호출) |
 
---

## 11. 트러블슈팅 기록

### 11.1 React Hooks 순서 에러
- **증상:** `Rendered more hooks than during the previous render`
- **원인:** `DirectorInterlude.jsx`에서 `if (!directive) return null;`이 `useEffect`/`useCallback` 선언 이전에 위치
- **수정:** 모든 훅을 최상단에 선언 후 early return을 훅 아래로 이동. `useMemo`로 directive 파생값을 null-safe하게 계산.

### 11.2 Redis 캐시 누락 (수동 요청)
- **증상:** 수동 요청 후 "디렉터 지시가 만료되었습니다" 에러
- **원인:** `requestManualIntervention()`이 Directive를 JSON으로 반환만 하고 Redis에 캐시하지 않아, 이후 `consumeDirective()`가 Redis에서 찾지 못함
- **수정:** 수동 요청에서도 `cacheDirective(roomId, directive)` 호출 추가. 자동/수동 두 경로의 라이프사이클 통일.

### 11.3 Jackson is*() 메서드 boolean 프로퍼티 오인식
- **증상:** Redis에 `"branch": true`로 저장됨 (BranchPayload 객체가 boolean으로 덮어써짐)
- **원인:** Java record의 `isBranch()` 메서드를 Jackson이 JavaBean boolean getter로 인식 → 동일 이름의 record component `branch`를 boolean으로 직렬화
- **1차 시도:** `@JsonIgnore` → 직렬화는 막히지만 역직렬화도 함께 막혀 payload가 항상 null
- **최종 수정:** `is*()` → `check*()` 메서드명 변경. `check*()` 패턴은 Jackson이 getter로 인식하지 않음.
- **영향 범위:** `DirectorDirective.java` (4개 메서드 + InterludePayload 1개), `DirectorService.java`, `ChatStreamService_Director_Patch.java`, `StoryController_Director.java` 총 18곳 일괄 변경.

### 11.4 LLM 타입 미스매치 (topic_concluded 무시)
- **증상:** `topic_concluded=true`인데 LLM이 INTERLUDE를 반복 출력 → 가드레일이 거부 → PASS 반환
- **원인:** 경량 모델(Gemini Flash)이 프롬프트의 타입 제약 지시를 무시
- **수정:** 1차 프롬프트에 금지 타입을 명시적으로 기재. 1차 실패 시 `buildStrictRetryPrompt()`로 2차 재시도 (❌ FORBIDDEN 3회 반복 강조).

### 11.5 INTERLUDE 자동 발동 미작동
- **증상:** INTERLUDE가 Redis에 캐시되지만 유저에게 표시되지 않음
- **원인:** `peekDirectorDirective()`가 유저의 `handleSendMessage()` 진입 시에만 호출. 유저가 메시지를 보내지 않으면 캐시된 Directive가 영원히 소비되지 않음.
- **수정:** `scheduleDirectorAutoCheck()` 추가. final_result 수신 후 4초 딜레이로 자동 peek 수행. 유저가 4초 이내에 메시지를 보내면 타이머 취소 + 기존 수동 체크가 대체.

---

## 12. 향후 최적화 포인트

### 12.1 디렉터 LLM 호출 빈도 최적화
현재: 가드레일 통과 시 매번 LLM 호출 (PASS 연속 구간에서 불필요한 호출 발생)
개선안: LLM 호출 전 `shouldCallDirectorLlm()` 사전 평가 함수 도입
- 최소 5턴 경과 + 대화 루즈함 감지 (스탯 변화량 정체) 시에만 LLM 호출
- PASS 연속 시 호출 간격을 점진적으로 늘리는 백오프 로직

### 12.2 디렉터 프롬프트 캐싱
정적 규칙 부분에 `cache_control` 적용 가능 (기존 CharacterPromptAssembler 캐싱 패턴 재활용)

### 12.3 디렉터 품질 모니터링
- PASS/INTERLUDE/BRANCH/TRANSITION 비율 로깅
- 타입 미스매치(재시도) 빈도 추적
- 유저 행동 패턴 (관찰 vs 개입 선택 비율) 분석