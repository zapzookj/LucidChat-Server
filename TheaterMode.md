# Lucid Chat — Phase 5.5 Theater Mode 종합 문서

> **문서 목적:** Theater 모드의 기획 의도·구현 내용·폴리싱 이력을 전부 집약한 세션 이관용 마스터 문서  
> **대상 버전:** Phase 5.5 Session 5 + Polish Session 6 + Polish v2  
> **작성 시점:** 2026-04-24  
> **상태:** 구현 완료 · 유저 테스트 중 · 폴리싱 2차까지 진행  
> **총 산출물:** 백엔드 57 파일 + 프론트엔드 21 파일 + SQL 2 파일 + 가이드 문서 6 파일 = **86 파일**

---

# 목차

1. [프로젝트 맥락](#1-프로젝트-맥락)
2. [Theater 모드 기획 개요](#2-theater-모드-기획-개요)
3. [핵심 설계 결정](#3-핵심-설계-결정)
4. [도메인 모델](#4-도메인-모델)
5. [서사 구조](#5-서사-구조)
6. [기능 명세](#6-기능-명세)
7. [아키텍처](#7-아키텍처)
8. [전체 파일 매니페스트](#8-전체-파일-매니페스트)
9. [구현 상세](#9-구현-상세)
10. [유저 테스트 결과 & 폴리싱 이력](#10-유저-테스트-결과--폴리싱-이력)
11. [알려진 제약 & 잠재 이슈](#11-알려진-제약--잠재-이슈)
12. [다음 세션 인수인계](#12-다음-세션-인수인계)

---

# 1. 프로젝트 맥락

## 1.1 Lucid Chat이란
AI 기반 미연시(연애 시뮬레이션) / 비주얼 노벨 플랫폼. Spring Boot(Java 17) 백엔드와 React 19 프론트엔드의 풀스택 웹 서비스.

## 1.2 Phase 변천
- **Phase 1~2**: MVP, 나레이션 엔진 기초
- **Phase 2.5**: AWS 베타, Secret Mode, 3지선다 이벤트
- **Phase 3**: RAG, SSE 스트리밍, Redis 캐싱
- **Phase 4**: Two-Track 아키텍처 (Story/Sandbox), 로비, 엔딩 시스템, 이스터에그, 업적
- **Phase 5**: 수익화(에너지 경제), 구독(Lucid Pass Standard/Premium), NICE 성인 인증, Content Moderation
- **Phase 5.5 Session 1**: BPM 같은 입체적 상태창, Inner Thought
- **Phase 5.5 Session 2**: 이벤트 고도화, LoRA 파인튜닝 캐릭터 일러스트
- **Phase 5.5 Session 3**: 프롬프트 캐싱, SSE 첫 씬 선행 추출
- **Phase 5.5 Session 4**: Fal.ai 일러스트 생성, 동적 배경 생성, 장소 전환 시스템, 다인큐 채팅(Lucid Lounge) 예정
- **Phase 5.5 Session 5**: ⭐ **Theater 모드** — 이 문서의 주제
- **Phase 5.5 Polish Session 6 + v2**: Theater 유저 테스트 피드백 대응

## 1.3 Theater 모드가 나온 이유
기존 Dialogue 모드(Story/Sandbox)는 유저가 항상 텍스트를 입력해야 진행되는 1:1 대화 구조. 이 구조는:
- 유저 피로감 유발 (매번 뭘 써야 하나 고민)
- LLM 비용이 매 턴 발생
- 서사의 기승전결이 유저 입력에 의존

Theater 모드는 **감상형 비주얼 노벨**로 이 한계를 극복:
- 유저는 감독처럼 스토리를 **선택**만 하고, LLM이 영화처럼 **배치 단위로 전개**
- 긴 서사(500~1,000 Scene, 2.5~4시간)를 지원
- Act → Chapter → Scene 구조로 분명한 기승전결
- 멀티 히로인·세계관 확장 가능성

---

# 2. Theater 모드 기획 개요

## 2.1 한 줄 정의
> **"유저가 직접 대화하지 않아도 LLM이 자동으로 비주얼 노벨을 전개하는 감상형 플레이 모드"**

## 2.2 Dialogue 모드와의 차이

| 항목 | Dialogue (Story/Sandbox) | Theater |
|------|-------------------------|---------|
| 유저 행동 | 매 턴 텍스트 입력 | 주로 선택만, 가끔 '난입' |
| 시점 | 1인칭 (내가 주인공) | 3인칭 (내 페르소나인 주인공을 바라봄) |
| 서사 길이 | 세션당 10~30턴 | 세션당 500~1,000 Scene |
| LLM 호출 단위 | 턴당 1회 | **배치(5~8 Scene)당 1회** |
| 캐릭터 | 단일 히로인 | **멀티 히로인** (세계관별 1~3명) |
| 엔딩 | 호감도 단일축 | **호감도 + 지배 스탯 9종 다축** |
| 세이브 | 자동 (ChatRoom) | **수동 5슬롯 + Quick Save** |

## 2.3 핵심 키워드
- **세계관(World)**: 중세 판타지, 동양 판타지, 현대 한국 등 3개로 출발. 세계관별 히로인 세트 고정.
- **Act**: 4막 구조 (만남 → 관계 → 전환 → 결말)
- **Chapter**: Act당 5~8개
- **Scene**: Chapter당 25~40개 (한 Scene = narration + inner_narration + dialogue)
- **배치(Batch)**: LLM이 한 번에 생성하는 Scene 묶음, 5~8개
- **아바타 스탯**: Charm/Wit/Boldness/Intellect/Empathy 5축
- **페르소나-스탯 하이브리드**: 유저가 정한 페르소나(자기 인식) vs 게임 스탯(객관적 현실)의 갭이 서사 연료
- **분기**: LOCATION(장소), MINOR(톤), MAJOR(Chapter 방향), CLIMAX(Act 운명)
- **인터미션**: Act 사이의 스탯 성장 미니게임 (피로도 5 + 특별 활동 5종)
- **난입(Intervention)**: Theater 감상 중 유저가 1인칭으로 잠깐 개입하는 기능
- **감독 노트(Director Note)**: 자동 캡처 + 수동 메모가 섞인 회고 시스템

---

# 3. 핵심 설계 결정

## 3.1 "한 씬 한 화자" 원칙
멀티 히로인이어도 한 Scene에는 최대 한 명의 히로인만 대사한다.
- **이유 1**: 토큰 폭증 방지 (히로인 N명 × 대사 → 프롬프트가 곱절)
- **이유 2**: 페르소나 블리딩 방지 (한 프롬프트 안에서 여러 캐릭터 정체성이 섞이면 개별성이 희석)
- **이유 3**: UI 복잡도 (말풍선이 여러 개면 비주얼 노벨 문법이 깨짐)
- **멀티 히로인의 풍부함은 유지됨**: 현재 화자가 아닌 히로인도 나레이션에서 등장·언급 가능

## 3.2 페르소나-스탯 하이브리드
- **페르소나** = 유저가 만든 아바타의 자기 인식 (성격, 백스토리, 자유 텍스트)
- **스탯** = 객관적 현실 (Charm 10 = 남들이 보기엔 별볼일 없음)
- **둘의 갭이 서사의 연료**: "나는 매력적이라고 생각하지만 주변 반응은 다르다" 같은 전형을 LLM이 inner_narration vs 히로인 반응에서 자연스럽게 연출

## 3.3 배치 생성 + Prefetch
- LLM을 매 Scene 호출하면 지연이 심함 → 5~8 Scene을 한 번에 생성
- 유저가 배치의 **70% 지점** 도달 시 다음 배치를 **비동기 prefetch**
- Redis 캐시 (TTL 6시간)
- 분기/난입 발생 시 캐시 무효화

## 3.4 Act 기반 히로인 분배 + 장소 선택 하이브리드
- **Act 1**: 각 히로인과 공평하게 등장
- **Act 2**: 호감도 비례 등장 확률
- **Act 3**: 수렴 시작 (최고 호감도 히로인 80%)
- **Act 4**: 메인 히로인 고정
- **각 Chapter 초입 장소 선택**: 멀티 히로인 세션에서 유저가 "어디로 갈까?" 선택해 다음 전개의 방향성 결정 (감독 의도 표현)

## 3.5 호감도 속도 제한 (Polish 이후)
- LLM은 배치당 호감도 변화 **[-2, +2]** 범위만 허용
- 대부분은 0 또는 ±1, +2는 감정적 전환점에만
- 서버 측 이중 클램프 (LLM 불복 대비)
- Chapter 종료 시 호감도 총변화는 +10~+15 목표 (긴 호흡 유지)

## 3.6 BM 구조
| 티어 | 초기 스탯 배분 | 상한 |
|------|------------|------|
| 무료 | 0 포인트 (전부 0부터 시작) | - |
| Lucid Pass Standard | 20 포인트 분배 | 단일 스탯 10까지 |
| Lucid Pass Premium | 40 포인트 + 프리셋 | 단일 스탯 20까지 |

**철학**: Pay-to-Skip / Pay-to-Express (유료 유저가 더 빨리 시작하거나 더 섬세한 페르소나 표현 가능). **Pay-to-Win 아님** — 스탯으로 서사가 빨리 끝나지 않음.

## 3.7 다축 엔딩
| 호감도 | 지배 스탯 | 엔딩 |
|--------|---------|------|
| 70+ | 80+ CHARM | CHARM_ENDING (매혹적 해피) |
| 70+ | 80+ WIT | WIT_ENDING (재치 해피) |
| 70+ | 80+ BOLDNESS | BOLDNESS_ENDING (대담 해피) |
| 70+ | 80+ INTELLECT | INTELLECT_ENDING (지적 해피) |
| 70+ | 80+ EMPATHY | EMPATHY_ENDING (공감 해피) |
| 70+ | 80 미만 | CHARM_ENDING (기본 해피 폴백) |
| 20~69 | — | FADED_ENDING (스쳐간 인연) |
| -30~19 | — | BITTER_ENDING (엇갈린 마음) |
| -30 미만 | — | ENEMY_ENDING (원수) |

---

# 4. 도메인 모델

## 4.1 신규 Enum 7종

### ChatMode (확장)
```java
DIALOGUE_STORY, DIALOGUE_SANDBOX, THEATER  // THEATER 추가
```

### ChatModePolicy (확장)
Theater 전용 기능 플래그 15종 + 상수:
- `THEATER_CHAPTERS_PER_ACT_MIN = 5`, `MAX = 8`
- `THEATER_SCENES_PER_CHAPTER_MIN = 25`, `MAX = 40`
- `THEATER_BATCH_SIZE_MIN = 5`, `MAX = 8`
- `THEATER_PREFETCH_TRIGGER_RATIO = 0.7`
- `INTERMISSION_STAMINA_MAX = 5`
- `INTERMISSION_EXTRA_ENERGY_COST = 2`
- `INTERVENTION_ENERGY_COST = 2`
- `THEATER_MAX_SAVE_SLOTS = 5`

### WorldId
```java
MEDIEVAL_FANTASY, ORIENTAL_FANTASY, MODERN_KOREA
```

### AvatarStat
```java
CHARM, WIT, BOLDNESS, INTELLECT, EMPATHY
// 각각 0~100 clamp, 수치별 descriptor 제공
```

### TheaterAct
```java
ACT_1_MEETING("만남"),
ACT_2_RELATION("관계"),
ACT_3_TRANSITION("전환"),
ACT_4_RESOLUTION("결말")
```

### BranchLevel
```java
LOCATION  // 장소 선택 (Chapter 초입, 결정론적)
    MINOR     // 톤 조정 (2지선다)
MAJOR     // Chapter 방향 (3지선다)
    CLIMAX    // Act 운명 (3지선다)
```

### TheaterEndingType
9종 (위 3.7 참조)

## 4.2 신규 엔티티 7종 (MariaDB 5 + MongoDB 1 + 정적 1)

### World (MariaDB)
세계관 마스터 테이블. id(WorldId), displayName, tagline, description, heroImageUrl, thumbnailUrl, moodKeywords.

### TheaterState (MariaDB)
ChatRoom 1:1. Theater 진행 전체 상태 집약.
- 서사: currentAct, currentChapter, scenesInCurrentChapter, chapterTargetScenes, totalSceneCount, currentBatchId, currentHeroineId
- 아바타: avatarName, avatarProfileJson, avatarPersonaText
- 5축 스탯: statCharm/Wit/Boldness/Intellect/Empathy
- 인터미션: intermissionStamina, inIntermission
- 엔딩: endingReached, endingType, endingTitle, endingMainHeroineId
- 난입: interventionActive, interventionCheckpointJson, interventionLastLogId
- 설정: autoPlayEnabled, playSpeed
- 메서드: addScenes, advanceBatch, completeChapter, advanceToNextAct, applyStatChange, dominantStat, enterIntervention, exitIntervention, markEndingReached, **restoreFromSnapshot**(세이브 로드용)

### TheaterHeroineAffection (MariaDB)
방+히로인별 호감도 (멀티 히로인 대응).
- affection, lastChapterDelta, runningDelta, totalScenes, chapterHighlightQuote, confirmedMain
- 메서드: applyDelta, sealChapterDelta, recordAppearance

### TheaterBranchChoice (MariaDB)
분기 이력 append-only. level, actNumber, chapterNumber, optionsJson, chosenIndex, chosenLabel.

### TheaterSaveSlot (MariaDB)
slotNumber(0=Quick, 1~5=수동), label, previewText, snapshotJson, isQuickSave.

### TheaterDirectorNote (MariaDB)
자동 + 수동 노트. noteType(AUTO_MOMENT/CHAPTER_END/INTERVENTION/MANUAL), content.

### TheaterSceneLog (MongoDB, Polish 1 추가)
Scene 영구 저장. roomId, actNumber, chapterNumber, batchId, sceneIndexInBatch, sceneSeqInChapter, globalSceneSeq, narration, innerNarration, dialogue, speakerType(HEROINE/AVATAR/null), speakerName, heroineId, emotion, location, timeOfDay, outfit, bgmMode, illustrationUrl.
- 인덱스 3개 (room+created, room+act+chapter, room+batch)
- TTL 없음 (엔딩 후에도 보관 — Memory Highlights 소스)

### IntermissionCatalog (정적 카탈로그)
DB 테이블이 아닌 **코드 상수**. 5개 기본 활동 + 5개 특별 활동 (15% 확률 등장).
- 기본: 사색/수련/교류/독서/수면 (각 타깃 스탯 1종)
- 특별: 우연한 만남/낯선 이와의 대화/거울 앞/편지 쓰기/꿈결

## 4.3 Character 엔티티 확장 (기존 수정)
Theater 모드 대응 4필드 추가:
- `worldId`: 이 캐릭터가 속한 세계관
- `homeLocations`: 이 캐릭터의 허용 장소들 (쉼표 구분 문자열)
- `theaterAvailable`: Theater 모드에 등장 가능한지
- `theaterIntroBeat`: Theater 오프닝에 쓸 첫 씬 힌트

## 4.4 DB 마이그레이션
`V20250417__phase_5_5_theater.sql`:
- 5개 Theater 테이블 신규
- characters 4컬럼 추가
- worlds 테이블 + 3개 세계관 시드
- 기존 캐릭터 4종(아이리/연화/서태리/백루나) worldId 연결

---

# 5. 서사 구조

## 5.1 3계층 구조
```
Session (1회 플레이)
└── 4 Acts
    ├── 5~8 Chapters (Act당)
    │   ├── 25~40 Scenes (Chapter당)
    │   │   ├── 배치 단위로 LLM 생성 (5~8 Scenes)
    │   │   └── Scene = narration + inner_narration + dialogue
    │   └── Chapter 종료: 리포트 모달
    └── Act 종료: 인터미션 (스탯 성장)

총 분량: 4 × 6 × 30 = 720 Scenes (평균)
        → 약 100~150 LLM 호출 / 2.5~4시간
```

## 5.2 각 Act의 서사 역할
- **Act 1 - 만남**: 모든 히로인과 평등하게 첫인상 (목표 호감도: 각 히로인 ~20)
- **Act 2 - 관계**: 호감도 비례 등장 (리드 히로인 ~40, 서브 히로인들 상대적 저하)
- **Act 3 - 전환**: 메인 수렴 시작 (리드 ~60, 서브는 정적)
- **Act 4 - 결말**: 메인 히로인 고정 (리드 ~80+ → 엔딩)

## 5.3 Scene 구성 요소
```json
{
  "narration": "3인칭 서술. 장면과 행동 묘사.",
  "inner_narration": "주인공의 속마음 (optional)",
  "dialogue": "히로인 or 주인공의 대사 (optional)",
  "speaker": "HEROINE | AVATAR | null",
  "emotion": "NEUTRAL | JOY | SAD | ANGRY | SHY | ...",
  "location": "BEDROOM | GARDEN | ...",  // enum
  "time": "DAY | NIGHT | DAWN | SUNSET | MORNING | AFTERNOON | EVENING",
  "outfit": "MAID | CASUAL | ...",       // enum
  "bgm_mode": "DAILY | ROMANTIC | EXCITING | TOUCHING | TENSE | EROTIC",
  "stat_reflection_hint": "스탯이 이 씬에 어떻게 반영됐는지 힌트"
}
```

---

# 6. 기능 명세

## 6.1 로비 진입
- `GET /api/v1/theater/lobby/worlds`: 3개 세계관 카드
- `GET /api/v1/theater/lobby/sessions`: 내 Theater 세션 (Continue)
- `POST /api/v1/theater/lobby/sessions`: 새 세션 생성 (4단계 플로우)
    1. 히로인 선택 (멀티 세계관은 1~3명)
    2. 아바타 기본 정보 (성별/나이대/체형/외형)
    3. 성격 & 백스토리 (성격 태그 최대 5개, 자유 페르소나 텍스트)
    4. 스탯 분배 (구독 티어별 포인트)

## 6.2 씬 재생
- `POST /api/v1/theater/rooms/{roomId}/next-batch`: 다음 배치 요청 (에너지 1)
- `POST /api/v1/theater/rooms/{roomId}/batch-consumed`: 배치 소비 완료 신호
- `POST /api/v1/theater/rooms/{roomId}/chapter-end`: Chapter 종료 처리
- `POST /api/v1/theater/rooms/{roomId}/prefetch`: 비동기 prefetch 트리거
- `PATCH /api/v1/theater/rooms/{roomId}/play-settings`: 자동재생/속도 설정

## 6.3 분기
- `POST /api/v1/theater/rooms/{roomId}/branches/location`: Chapter 초입 장소 선택 (결정론적)
- `POST /api/v1/theater/rooms/{roomId}/branches/scene`: 씬 분기 생성 (LLM)
- `POST /api/v1/theater/rooms/{roomId}/branches/choose`: 선택 확정

### Stat-gated Branch
일부 선택지에 `stat_gate: { stat: "CHARM", min_value: 30 }` 조건. 미충족 시 UI에서 잠금 표시 + 선택 불가.

## 6.4 인터미션
Act 사이에만 발생. 피로도 5 + 각 활동당 피로도 1 소모. 주사위 결과:
- **대성공** (10%): 스탯 +2
- **성공** (60~70%): 스탯 +1
- **실패** (20~30%): 스탯 변화 없음

**특별 활동** (15% 확률로 기본 활동 일부를 대체):
- 우연한 만남: 현재 리드 히로인 호감도 +3
- 낯선 이와의 대화: 랜덤 2개 스탯 +1
- 거울 앞: 랜덤 1개 스탯 +1 + 감독 노트 자동 생성
- 편지 쓰기: EMPATHY 중심 주사위
- 꿈결: 랜덤 2개 스탯 +1

## 6.5 난입(Intervention)
- `POST /api/v1/theater/rooms/{roomId}/intervention/start`: 난입 시작 (에너지 2)
    - 체크포인트 JSON 저장 (복귀용)
    - Redis 토큰 발급 (TTL 1시간)
    - 배치 캐시 무효화
    - 감독 노트 자동 기록
- `POST /api/v1/theater/rooms/{roomId}/intervention/resume`: Theater 흐름 복귀
    - 토큰 검증 + 소비
    - 개입 내용을 다음 배치 `branchContext`로 주입 → LLM이 자연스럽게 반영

**중요**: 난입 중에는 기존 Dialogue 모드 `ChatService`를 재활용. 기능별 정책 차이는 `ChatModePolicy`가 분기(Promotion/DirectorEngine/EasterEgg는 자동 비활성).

## 6.6 엔딩 & 세이브/로드
- `POST /api/v1/theater/rooms/{roomId}/ending`: 엔딩 트리거 (Act 4 종료 시)
    - 호감도 + 지배 스탯 → 엔딩 타입 결정
    - LLM으로 엔딩 씬 3개 생성
    - Memory Highlights (감독 노트 기반) 수집
- `GET /api/v1/theater/rooms/{roomId}/saves`: 슬롯 목록 (0=Quick, 1~5=수동)
- `POST /api/v1/theater/rooms/{roomId}/saves`: 세이브
- `POST /api/v1/theater/rooms/{roomId}/saves/{slotNumber}/load`: 로드 (TheaterState.restoreFromSnapshot)

## 6.7 감독 노트
- 자동 캡처: AUTO_MOMENT(특별 인터미션 성공 등), CHAPTER_END, INTERVENTION
- 수동 작성: MANUAL (CRUD)
- 엔딩 크레딧의 Memory Highlights 소스

## 6.8 대화 기록 (Polish 1 추가)
- `GET /api/v1/theater/rooms/{roomId}/scene-history/chapter/{act}/{chapter}`
- `GET /api/v1/theater/rooms/{roomId}/scene-history/paginated?page=&size=`
- `GET /api/v1/theater/rooms/{roomId}/scene-history/recent?count=`

---

# 7. 아키텍처

## 7.1 백엔드 레이어
```
Controller Layer (6개)
  TheaterLobbyController     — 세계관/세션 진입
  TheaterController          — 배치 요청/소비
  TheaterBranchController    — 분기 3종
  TheaterIntermissionController
  TheaterInterventionController
  TheaterFinalityController  — 엔딩/세이브/노트
  TheaterHistoryController   — 대화 기록 (Polish 1)
     ↓
Service Layer (12개)
  TheaterLobbyService
  TheaterService             — 배치 소비 오케스트레이션
  TheaterBatchGenerator      — LLM 호출 + 파싱 + MongoDB 저장
  TheaterBatchCacheService   — Redis prefetch 캐시
  TheaterDirectorEngine      — Chapter 계획, 히로인 분배, 수렴
  TheaterBranchService
  TheaterIntermissionService
  TheaterInterventionService — Redis 토큰 직접 관리
  TheaterEndingService
  TheaterSaveLoadService
  TheaterDirectorNoteService
  TheaterHistoryService      — (Polish 1)
     ↓
Prompt Layer
  TheaterPromptAssembler     — 3인칭 + inner_narration + 페르소나-스탯 하이브리드 프롬프트
     ↓
External Layer
  OpenRouterClient           — non-stream JSON 완성
     ↓
Domain Layer
  엔티티 7종 + 리포지토리 6종 + IntermissionCatalog (정적)
     ↓
Infra
  MariaDB + Redis + MongoDB
```

## 7.2 프론트엔드 레이어 구조 (비주얼 노벨)
```
TheaterPlayPage (컨테이너)
│
├── z-0: BackgroundDisplay (기존 재활용)
│         Scene.location + Scene.time → 배경 이미지 로드
│
├── z-0: CharacterDisplay (기존 재활용)
│         Scene.speakerType=HEROINE → emotion 반영
│         (narration only일 때는 NEUTRAL 고정)
│
├── 비시각: AudioEngine (기존 재활용)
│         Scene.bgmMode + Scene.location → BGM/앰비언스/SFX
│
├── z-20: 상단 HUD
│         세계관 이름, Act/Chapter 진행도
│         멀티 히로인 상태 카드 (멀티일 때)
│
├── z-30: TheaterDialogueBox (하단, Theater 전용)
│         순차 타자기: narration → inner_narration → [user_dialogue | heroine_dialogue]
│         유저 대사: 우측 시안 버블
│         히로인 대사: 좌측 앰버 버블
│         네비: 이전/다음 버튼, 속도, 자동/수동, 기록
│
└── z-60: 모달
          TheaterBranchModal / TheaterChapterReportModal
          TheaterSceneHistoryPanel / TheaterCinematicLoader
```

## 7.3 배치 Prefetch 파이프라인
```
유저가 Scene 5/8 감상 중
  → 70% 지점 도달 감지
  → POST /theater/rooms/{id}/prefetch
  → @Async("theaterPrefetchExecutor")로 TheaterService.prefetchNextBatchAsync()
  → TheaterBatchGenerator 호출
  → LLM 응답
  → Redis `theater:batch:{roomId}:{nextBatchId}` 저장 (TTL 6h)
  → MongoDB `theater_scene_logs` 영구 저장
  → 유저가 다음 배치 요청 시 캐시 HIT → 즉시 반환 (LLM 지연 0)
```

## 7.4 분기 발생 시 캐시 정책
1. 유저가 분기 선택
2. `TheaterBranchChoice` DB 저장 (append-only)
3. Redis 배치 캐시 전면 무효화: `theater:batch:{roomId}:*`
4. `branchContext` Redis 저장: `theater:branch:ctx:{roomId}:active`
5. 다음 배치 생성 시 branchContext가 프롬프트에 주입 → 유저 선택이 반영된 새 컨텍스트

## 7.5 탭 구조 (로비)
- `LobbyTabShell`이 최상위 컨테이너
- `LobbyPage`(기존 Dialogue용)는 항상 마운트 유지 — BGM 끊김 방지
- Theater 탭 활성 시 `visibility:hidden`만 처리, DOM은 유지
- 탭바는 LobbyPage의 `topbarExtras` prop으로 주입 (레이아웃 일관성)

---

# 8. 전체 파일 매니페스트

## 8.1 Phase 5.5 Session 5 생성 파일 (68개)

### Enum & 마이그레이션 (9개)
```
enums/ChatMode.java                  (기존 덮어쓰기)
enums/ChatModePolicy.java            (기존 덮어쓰기)
enums/WorldId.java
enums/AvatarStat.java
enums/TheaterAct.java
enums/BranchLevel.java
enums/TheaterEndingType.java
migration/V20250417__phase_5_5_theater.sql
migration/V20250417_2__chat_rooms_theater_constraint_note.sql
```

### 엔티티 & 리포지토리 (17개)
```
domain/character/Character.java                   (기존 덮어쓰기)
domain/character/CharacterRepository.java         (기존 덮어쓰기)
domain/chat/ChatRoomRepository.java               (기존 덮어쓰기)
config/CharacterSeedProperties.java               (기존 덮어쓰기)
domain/theater/World.java
domain/theater/WorldRepository.java
domain/theater/TheaterState.java
domain/theater/TheaterStateRepository.java
domain/theater/TheaterHeroineAffection.java
domain/theater/TheaterHeroineAffectionRepository.java
domain/theater/TheaterBranchChoice.java
domain/theater/TheaterBranchChoiceRepository.java
domain/theater/TheaterSaveSlot.java
domain/theater/TheaterSaveSlotRepository.java
domain/theater/TheaterDirectorNote.java
domain/theater/TheaterDirectorNoteRepository.java
domain/theater/IntermissionCatalog.java
```

### DTO & Config (6개)
```
dto/theater/AvatarProfile.java
dto/theater/LlmSceneBatchOutput.java
dto/theater/TheaterRequests.java
dto/theater/TheaterResponses.java
dto/theater/TheaterStreamEvents.java
config/TheaterConfig.java
```

### 서비스 & 외부 클라이언트 (13개)
```
external/OpenRouterClient.java
service/prompt/TheaterPromptAssembler.java
service/theater/TheaterLobbyService.java
service/theater/TheaterService.java
service/theater/TheaterBatchGenerator.java
service/theater/TheaterBatchCacheService.java
service/theater/TheaterDirectorEngine.java
service/theater/TheaterBranchService.java
service/theater/TheaterIntermissionService.java
service/theater/TheaterInterventionService.java
service/theater/TheaterEndingService.java
service/theater/TheaterSaveLoadService.java
service/theater/TheaterDirectorNoteService.java
```

### 컨트롤러 (6개)
```
controller/TheaterLobbyController.java
controller/TheaterController.java
controller/TheaterBranchController.java
controller/TheaterIntermissionController.java
controller/TheaterInterventionController.java
controller/TheaterFinalityController.java
```

### 프론트엔드 API & 훅 (5개)
```
src/api/TheaterLobbyApi.js
src/api/TheaterPlayApi.js
src/api/TheaterGameplayApi.js
src/api/TheaterFinalityApi.js
src/hooks/useTheaterStream.js
```

### 프론트엔드 UI (9개)
```
src/pages/LobbyTabShell.jsx
src/pages/TheaterPlayPage.jsx
src/pages/TheaterIntermissionPage.jsx
src/pages/TheaterEndingCredits.jsx
src/components/theater/TheaterLobbyTab.jsx
src/components/theater/TheaterCreateFlow.jsx
src/components/theater/TheaterBranchModal.jsx
src/components/theater/TheaterChapterReportModal.jsx
src/components/theater/TheaterSaveLoadPanel.jsx
```

### 가이드 문서 (3개)
```
PHASE_5_5_SESSION_5_REPORT.md
_routing_guide.md
_chat_service_intervention_guide.md
```

## 8.2 Polish Session 6 (v1) 추가 파일 (14개)

### 백엔드 (7개)
```
domain/theater/TheaterSceneLog.java                 (신규 - MongoDB)
domain/theater/TheaterSceneLogRepository.java       (신규)
service/theater/TheaterBatchGenerator.java          (덮어쓰기 — Scene 로그 저장 + 호감도 클램프 + 최근 10씬 기억 주입)
service/prompt/TheaterPromptAssembler.java          (덮어쓰기 — 서사 페이싱 섹션 + 호감도 범위 강화)
service/theater/TheaterHistoryService.java          (신규)
controller/TheaterHistoryController.java            (신규)
_TheaterResponses_patch_scene_history.md            (가이드)
```

### 프론트엔드 (7개)
```
src/components/theater/TheaterCinematicLoader.jsx   (신규 — 영사기 필름 릴 로더)
src/components/theater/TheaterDialogueBox.jsx       (신규 — 하단 박스 + 순차 타자기)
src/components/theater/TheaterSceneHistoryPanel.jsx (신규 — 대화 기록 모달)
src/components/theater/TheaterChapterReportModal.jsx(덮어쓰기 — Act 진행도 표시)
src/pages/TheaterPlayPage.jsx                       (덮어쓰기 — 비주얼 노벨 레이어 통합)
src/pages/LobbyTabShell.jsx                         (덮어쓰기 — 탭바 LobbyPage 통합 + BGM 유지)
_LobbyPage_patch_guide.md                           (가이드)
```

## 8.3 Polish v2 (에셋 불일치 수정) 추가 파일 (8개)

### 백엔드 (5개)
```
dto/theater/TheaterResponses.java                (덮어쓰기 — 필드 5개 추가/변경)
service/prompt/TheaterPromptAssembler.java       (덮어쓰기 — 히로인 정체성 깊이 주입 + enum 세트 강제)
service/theater/TheaterBatchGenerator.java       (덮어쓰기 — location/outfit sanitize)
_TheaterLobbyService_patch_guide.md              (가이드 — 3곳 수정)
_TheaterService_patch_guide.md                   (가이드 — 1곳 수정)
```

### 프론트엔드 (3개)
```
src/pages/TheaterPlayPage.jsx                       (덮어쓰기 — avatar.name/actTotalChapters 안전 해결)
src/components/theater/TheaterLobbyTab.jsx          (덮어쓰기 — characterSlug 폴백 + 세션카드 썸네일)
src/components/theater/TheaterChapterReportModal.jsx(덮어쓰기 — 썸네일 폴백)
```

**총계**: 68 + 14 + 8 = **90개 파일 산출** (일부는 여러 번 덮어쓰기됨)

---

# 9. 구현 상세

## 9.1 프롬프트 설계 (TheaterPromptAssembler)

### 블록 구성
1. **System Role**: "당신은 비주얼 노벨 감독"
2. **Narrative Principles**: 3인칭, inner_narration 분리, 단일 화자
3. **Avatar Profile**: 유저 페르소나 (이름/성별/나이대/체형/외형/성격태그/백스토리/자유 텍스트)
4. **Avatar Stats**: 5축 + 수치별 descriptor
5. **Current Scene's Speaker (Identity Anchor)** — [Polish v2에서 대폭 강화]
    - 히로인 이름/slug
    - Role, Personality(secret mode aware), Tone
    - OOC Deflection Example
    - Story Behavior Guide (relation-aware)
    - Default Location/Outfit
    - **Allowed Location/Outfit enum sets** — LLM이 임의 문자열 생성 방지
    - Theater Intro Beat
    - "⚠️ Keep this heroine's distinctive voice" 강조
6. **All Heroines in This Session**: 멀티 히로인 요약
7. **Narrative Progress**: Act/Chapter/Scene 현황
8. **Chapter Direction Hint**: 디렉터 엔진이 미리 결정
9. **Rolling Summary**: 이전 배치 연속성
10. **Recent Scenes Memory** — [Polish 1 추가] 최근 10 Scene 요약을 직접 주입 → 장기 기억
11. **Batch Instruction**: 정확한 Scene 수 명시
12. **Narrative Pacing** — [Polish 1 추가] 호감도 배치당 [-2, +2] 강제
13. **Secret Mode**: 시크릿 모드 해제 시에만
14. **Output Format**: JSON 스키마, location/outfit enum 강제

### Anti-Drift 전략
네 지적 "Theater가 기본 아이리 분위기로 수렴"을 해결하기 위한 3중 방어:
1. 프롬프트에 히로인 정체성을 10개 필드로 깊이 주입 (Role, Personality, Tone, OOC, BehaviorGuide, Defaults, Allowed Sets, IntroBeat)
2. "⚠️ This speaker's IDENTITY MUST remain consistent" 명시 경고
3. 서버 측 sanitize로 LLM 응답의 location/outfit이 허용 세트 밖이면 캐릭터 기본값 폴백

## 9.2 배치 생성기 (TheaterBatchGenerator) 흐름

```
1. TheaterDirectorEngine.decideNextSpeakerHeroine() — Act 기반 분배
2. TheaterPromptAssembler.assembleBatchPrompt() — 시스템 프롬프트 조립
3. Recent Scenes Memory 주입 (최근 10 Scene, MongoDB 조회)
4. OpenRouterClient.completeJson() — LLM 호출
5. JSON 파싱 → LlmSceneBatchOutput
6. validateBatch() — Scene 수, speaker slug 검증
7. convertToSceneBatch() — SceneBatch DTO 생성
   ├─ location/outfit/time/bgm sanitize (enum 검증)
   └─ 호감도 델타 clampAffectionDelta() — [-2, +2] 강제
8. persistSceneLogs() — MongoDB 영구 저장
9. batchCache.putBatch() — Redis 캐시
10. rollingSummary 업데이트
```

## 9.3 디렉터 엔진 (TheaterDirectorEngine)
**LLM과 독립적인 서사 메타 레이어**. 결정론적으로 다음을 계산:
- `decideNextSpeakerHeroine(room, state, hint)`: Act 기반 히로인 분배
    - Act 1: round-robin
    - Act 2: 호감도 비례 확률
    - Act 3: 리드 히로인 80%
    - Act 4: 메인 히로인 고정
- `generateChapterPlanHint(state, speaker)`: 이 Chapter가 어느 방향으로 가야 하는지 힌트
- `confirmMainHeroineIfApplicable(room, state)`: Act 전환 시 메인 확정
- `isLastChapterOfAct(state)`: Act 종료 판정
- `decideChapterTargetScenes(state, isLast)`: 다음 Chapter의 목표 Scene 수

## 9.4 세이브/로드 스냅샷
```json
{
  "stateSnapshot": {
    "currentAct": "ACT_2_RELATION",
    "currentChapter": 3,
    "scenesInCurrentChapter": 15,
    "chapterTargetScenes": 30,
    "totalSceneCount": 127,
    "currentHeroineId": 45,
    "currentBatchId": 2,
    "statCharm": 25, "statWit": 18, ...,
    "intermissionStamina": 5
  },
  "affections": [
    { "characterId": 45, "affection": 42, "totalScenes": 80, "confirmedMain": false }
  ],
  "savedAt": "2026-04-23T14:30:00"
}
```
로드 시 `TheaterState.restoreFromSnapshot()` 호출 + TheaterHeroineAffection 업데이트. 엔딩/인터미션/난입 플래그는 리셋.

**한계**: Scene 로그는 append-only라 삭제 불가. 로드 시점 이후의 로그는 여전히 DB에 남아있음 (UI에서 totalSceneCount 기준으로 가림).

## 9.5 프론트엔드 핵심: TheaterDialogueBox
**Polish 1의 핵심 신규 컴포넌트**. 이슈 1(나레이션/대사 순차 출력) 해결의 주역.

### 순차 타자기 (useSequentialTypewriter 훅)
기존: narration/inner/dialogue 각각 독립 useTypewriter → 빈 text일 때 즉시 done → 동시 출력.
해결: 하나의 훅이 parts 배열 순서대로 처리. 빈 파트는 skip. 완료 시에만 다음 파트 시작.

### Parts 구성
```javascript
[
    { key: "narration", text: scene.narration },
    { key: "inner", text: scene.innerNarration },
    // speakerType에 따라 둘 중 하나만
    { key: "dialogue_user", text: scene.dialogue }     // speakerType=AVATAR
    { key: "dialogue_heroine", text: scene.dialogue }  // speakerType=HEROINE
]
```

### 유저 vs 히로인 대사 분리
- 유저: 우측 정렬, 시안 버블 (cyan-500/15)
- 히로인: 좌측 정렬, 앰버 버블 (amber-500/10)

### 버튼 전용 클릭
최상위 박스는 `onClick={stopPropagation}`. 버튼에만 핸들러 연결.
"다음" 버튼은 타이핑 미완료 시 "건너뛰기"로 자동 변경 → 클릭 시 skipAll 호출.

---

# 10. 유저 테스트 결과 & 폴리싱 이력

## 10.1 Polish 1 (Session 6) — 9개 UX 이슈

| # | 이슈 | 원인 | 해결 |
|---|------|------|------|
| 1 | 나레이션/대사 동시 출력 | 독립 훅의 즉시 완료 | useSequentialTypewriter |
| 2 | 비주얼 노벨 부재 (치명) | 기존 에셋 미활용 | BackgroundDisplay+CharacterDisplay+AudioEngine 통합 |
| 3 | 화면 아무데나 클릭 전환 | 최상위 onClick | 버튼에만 핸들러 |
| 4 | 이전/기록 부재 | Scene 영구 저장 없음 | MongoDB TheaterSceneLog + 3개 조회 API + 기록 패널 |
| 5 | 로딩 마스킹 빈약 | "로딩..." 텍스트만 | TheaterCinematicLoader (영사기 릴) |
| 6 | 호감도 과속 상승 | LLM 범위 [-5,+5] 허용 | [-2,+2] 이중 클램프 (프롬프트+서버) |
| 7 | 아바타 기억 초기화 | RAG 미연계 | 최근 10 Scene 프롬프트 주입 |
| 8 | Chapter 간 내용 미연결 | 이슈 7과 동일 | 동일 해결 + Act 진행도 UI |
| 9 | 로비 UI 깨짐 + BGM 끊김 | LobbyPage 외부 탭바 + unmount | 탭바 Topbar 주입 + visibility:hidden |

**이슈 8의 설계 맥락 명확화**: 인터미션은 Act 경계에서만 발생(정상 설계). Chapter 1은 Act 1 내부. 유저 혼란을 방지하기 위해 Chapter Report에 "Act 종료까지 N Chapter 남음" 표시 추가.

## 10.2 LobbyTabShell 버그 (Polish 1 직후)
**증상**: Theater 탭에서 세계관 클릭해도 반응 없음.  
**원인**: prop 이름 불일치. `TheaterLobbyTab`은 `onCreateFlow`를 받는데 `LobbyTabShell`은 `onSelectWorld`로 넘김. `onCreateFlow && onCreateFlow(w)` 단락평가로 조용히 실패.  
**수정**: `onCreateFlow={onOpenCreateFlow}`로 교정.

## 10.3 Polish v2 — 에셋 불일치 전면 수정

### 증상
> "캐릭터가 누구든 디폴트 아이리 에셋(캐릭터 이미지/배경/BGM)만 로드됨"

### 원인 4가지
1. **DTO 필드명 불일치**: 서버는 `slug`, 프론트는 `characterSlug`. 필드가 undefined → resolve 함수의 `|| "airi"` 폴백.
2. **TheaterSessionCard/HeroineReportItem에 characterSlug 자체 누락**.
3. **Theater 프롬프트가 히로인 정체성을 얇게만 주입**: Dialogue는 10개 필드 참조, Theater는 4개만. 허용 location/outfit enum 세트를 안 내려줘서 LLM이 자유 문자열 반환 → BackgroundDisplay.resolveBackground가 매칭 실패 → 폴백.
4. **서버 측 enum 검증 부재**: LLM이 `location: "달빛 정원"` 반환하면 그대로 프론트에 전달.

### 해결 (5중 방어)
1. **DTO 필드명 통일** (`TheaterResponses.java`):
    - `HeroineAffectionSnapshot.slug` → `characterSlug`
    - `HeroineSummary.slug` → `characterSlug`
    - `HeroineReportItem`에 `characterSlug` 추가
    - `NarrativeProgress`에 `actTotalChapters` 추가
    - `TheaterSessionCard`에 `leadHeroineSlug`, `leadHeroineThumbnailUrl` 추가
    - `TheaterRoomInfo`에 flat `avatarName` 추가

2. **서비스 DTO 생성부 패치** (TheaterLobbyService 3곳, TheaterService 1곳)

3. **프롬프트 보강** (TheaterPromptAssembler):
    - Speaker 블록을 Dialogue 수준으로 확장 (10개 필드)
    - Allowed Location/Outfit enum sets 명시 전달
    - Output Format에 "enum 값만 허용" 강제

4. **서버 측 sanitize** (TheaterBatchGenerator):
    - LLM 응답의 location/outfit/time/bgm을 enum 세트로 검증
    - 허용 세트 밖이면 캐릭터 기본값으로 폴백
    - `[SANITIZE]` 로그로 LLM 순응도 추적

5. **프론트엔드 폴백 보강**:
    - `characterSlug` 기반 이미지 경로 폴백 (`/characters/${slug}/thumb.jpg`)
    - 세션 카드에 리드 히로인 썸네일 추가
    - Chapter Report 히로인 카드에 캐릭터 에셋 경로

---

# 11. 알려진 제약 & 잠재 이슈

## 11.1 LLM 비용 (⚠️ 핵심 운영 리스크)
Theater의 LLM 호출 빈도는 Dialogue 대비 **60~80배**.
- 1 세션 완주: 80~150회 호출 × 배치당 2~4k 토큰
- **반드시 2단 모델 전략 적용 권장**:
    - 기본 배치: Gemini Flash 계열 (저비용)
    - CLIMAX 분기, 엔딩, 메인 확정 씬: Claude Sonnet 급 (고급)
- `TheaterBatchGenerator.invokeLlm()` 내부에 Act/BranchLevel 조건 분기 추가 필요

## 11.2 Redis 메모리
Prefetch 캐시 TTL 6시간. 대규모 동시 세션 시 메모리 모니터링 필요.
- 대안 1: TTL 2~3시간 단축
- 대안 2: Prefetch 최대 깊이 1로 제한

## 11.3 아직 해결되지 않은 UI 일관성 이슈
네가 지적한 "Theater가 기존과 따로 노는 느낌"은 Polish v2로 에셋 쪽은 해결됐지만, 다음은 후속 폴리싱 대기:
- Intermission 페이지의 배경/에셋도 로비와 일관성 점검 필요
- Ending Credits에 CharacterDisplay 재활용 검토
- Branch Modal에 히로인별 아이콘 차별화

## 11.4 구현되었지만 실제 사용되지 않은 인프라
- `TheaterStreamEvents.java` (8종 SSE 이벤트 DTO): 현재 구현은 배치 단위 non-streaming. 향후 Scene 단위 스트리밍 전환 시 사용.
- `RedisCacheService` 확장 가이드: 난입 토큰을 TheaterInterventionService가 StringRedisTemplate 직접 사용으로 대체하며 불필요해짐.

## 11.5 세이브/로드의 Scene 로그 한계
MongoDB 로그는 append-only. 로드 시점 이후 로그가 남아있음. UI는 totalSceneCount 기준으로 필터링하지만, 데이터베이스 직접 조회 시에는 보일 수 있음.

## 11.6 ChatService 난입 통합 가이드
`_chat_service_intervention_guide.md`에 기재된 기존 `ChatService.java` 수정 사항 — 실제 이식 시 반영 필요:
- THEATER 모드 + interventionActive=true인 방은 Dialogue 메시지 허용
- 메시지 전송 후 `theaterInterventionService.recordInterventionLog()` 호출
- 5축 히로인 스탯 업데이트 스킵 (Theater는 TheaterHeroineAffection 별도 관리)

## 11.7 기존 LobbyPage.jsx 수정 필요
`_LobbyPage_patch_guide.md`에 기재된 수정 사항:
- `topbarExtras` prop 추가 (탭바 주입용)
- 선택: `disableBgm` prop 추가 (탭 전환 간 BGM 제어 세분화)

## 11.8 Tailwind 동적 클래스
Theater UI는 `bg-${theme.primary}-500/10` 같은 템플릿 리터럴을 사용. Tailwind safelist 설정 권장:
```js
safelist: [
    { pattern: /bg-(pink|cyan|orange|indigo|rose|amber|purple|slate)-(300|400|500)\/[0-9]+/ },
]
```

---

# 12. 다음 세션 인수인계

## 12.1 즉시 테스트 필요한 항목
Polish v2 이식 직후 검증:
1. 로비 Theater 탭 → 세계관 진입 → 세션 생성 플로우 완주
2. **아이리가 아닌 캐릭터**(예: 연화) 선택한 세션에서:
    - 배경 이미지가 연화 저택으로 로드되는지 (Network 탭 `/backgrounds/yeonhwa/`)
    - 캐릭터 일러스트가 연화로 렌더되는지
    - BGM이 연화 테마로 재생되는지
3. 씬 재생 중 나레이션 → 대사 순차 출력 확인
4. 유저 대사(speaker=AVATAR)가 우측 시안 버블로 표시되는지
5. 화면 중앙 클릭해도 전환 안 되는지
6. 로딩 중 영사기 필름 릴 로더 표시되는지
7. Chapter 1 완주 후 Chapter 2에서 Chapter 1 내용이 기억되는지 (호감도/기억)
8. 대화 기록 패널에서 과거 씬 조회 가능한지

## 12.2 유저가 이미 지적했지만 완전히 해결되지 않은 이슈
- "Theater UI가 기존과 따로 노는 느낌" — 에셋 쪽은 Polish v2로 해결.
  하지만 Intermission 페이지, Ending Credits, Branch Modal 등의 UI 일체감은 아직 점검 필요.

## 12.3 유저 테스트 아직 진행 중
유저는 Polish v2 적용 후 에셋 로딩은 정상화됐다고 확인. Chapter 1 이후 구간의 테스트가 아직 끝나지 않음. 추가 이슈 발견 가능성 높음:
- 엔딩 생성 품질
- 분기 Stat-gate 동작
- 인터미션 주사위 결과 분포
- 세이브/로드 정확도
- 난입 왕복 복귀 시 맥락 연속성
- Branch Modal의 히로인 컨텍스트 반영도

## 12.4 파일 위치
- 최초 구현(68 파일): `/home/claude/theater/`
- Polish v1 (14 파일): `/home/claude/theater-polish/`
- Polish v2 (8 파일): `/home/claude/theater-polish-v2/`
- 사용자 출력: `/mnt/user-data/outputs/bundle_01~08/`, `polish_01~02/`, `polish_v2_asset_fix/`

## 12.5 구독 티어 연동
`TheaterCreateFlow.jsx`의 Step 4 스탯 분배가 구독 티어를 읽음:
```jsx
import { useAuth } from "../../context/AuthContext";
const { user } = useAuth();
user.subscriptionTier === "PREMIUM" ? 40 : user.subscriptionTier === "STANDARD" ? 20 : 0;
```
이 필드가 AuthContext에서 정상 공급되는지 기존 Phase 5 구현 확인 필요.

## 12.6 DB 세계관 에셋 배치
마이그레이션은 세계관 시드만 SQL에 삽입. 이미지 에셋은 정적 파일로 별도 배치 필요:
```
/public/worlds/medieval_fantasy/hero.jpg + thumb.jpg
/public/worlds/oriental_fantasy/hero.jpg + thumb.jpg
/public/worlds/modern_korea/hero.jpg + thumb.jpg
```
이 파일 없으면 세계관 카드가 CSS 폴백 그라디언트로 표시됨(기능은 정상).

## 12.7 다음 세션 우선순위 추천
1. **남은 UI 일관성 폴리싱** (유저 지적 연장선)
    - Intermission / Ending Credits / Branch Modal 기존 UX 언어 정렬
2. **LLM 2단 모델 전략 실구현**
    - TheaterBatchGenerator에 Act/BranchLevel 기반 모델 라우팅
3. **엔딩 시스템 검증**
    - 유저가 Act 4까지 완주하는 End-to-end 테스트 + 엔딩 씬 품질 확인
4. **장기 안정화**
    - 다양한 엣지 케이스 (세이브 중 분기, 난입 중 Chapter 종료 등) 회귀 테스트
5. **감독 노트 UI 완성**
    - 현재는 API만 있고 UI 미구현 (TheaterDirectorNoteService/Controller 존재, 프론트 가시화 부재)

## 12.8 이 문서의 범위
이 문서는 Theater 모드에만 집중. 다음 주제들은 이 문서 범위 밖:
- Dialogue 모드 (Story/Sandbox) 상세
- RAG 기반 장기 기억 (Phase 3)
- Fal.ai 일러스트 생성 (Phase 5.5 Session 4)
- 다인큐 채팅 / Lucid Lounge (Phase 5.5 Session 4, 별도 구현 예정)
- Kinetic Typography (Phase 5.5 Session 4)

각각의 기능 설계는 RoadMap.md와 기존 Phase 문서 참고.

---

# 부록 A: 주요 파일별 라인 수 (구현 규모 감)

| 파일 | 라인 수 | 역할 |
|------|--------|------|
| TheaterResponses.java | 390+ | 17개 DTO 레코드 |
| TheaterPromptAssembler.java | 340+ | 10+ 블록 프롬프트 조립 |
| TheaterBatchGenerator.java | 470+ | LLM 호출 + MongoDB + sanitize |
| TheaterService.java | 280+ | 배치 오케스트레이션 |
| TheaterLobbyService.java | 517 | 세계관/세션 생성 |
| TheaterEndingService.java | 300+ | 엔딩 씬 LLM 생성 |
| TheaterPlayPage.jsx | 564 | 비주얼 노벨 레이어 통합 |
| TheaterCreateFlow.jsx | 31KB | 4단계 세션 생성 모달 |
| TheaterDialogueBox.jsx | 400+ | 하단 박스 + 순차 타자기 |
| TheaterChapterReportModal.jsx | 427 | Chapter 종료 시네마틱 |
| TheaterEndingCredits.jsx | 300+ | 엔딩 5단계 연출 |

# 부록 B: REST 엔드포인트 전체 목록 (27개)

```
# 로비
GET    /api/v1/theater/lobby/worlds
GET    /api/v1/theater/lobby/worlds/{worldId}
GET    /api/v1/theater/lobby/sessions
POST   /api/v1/theater/lobby/sessions
GET    /api/v1/theater/rooms/{roomId}                     (재진입)
PATCH  /api/v1/theater/rooms/{roomId}/avatar
POST   /api/v1/theater/rooms/{roomId}/reroll

# 배치/Chapter 진행
POST   /api/v1/theater/rooms/{roomId}/next-batch
POST   /api/v1/theater/rooms/{roomId}/batch-consumed
POST   /api/v1/theater/rooms/{roomId}/chapter-end
POST   /api/v1/theater/rooms/{roomId}/prefetch
PATCH  /api/v1/theater/rooms/{roomId}/play-settings

# 분기
POST   /api/v1/theater/rooms/{roomId}/branches/location
POST   /api/v1/theater/rooms/{roomId}/branches/scene
POST   /api/v1/theater/rooms/{roomId}/branches/choose

# 인터미션
GET    /api/v1/theater/rooms/{roomId}/intermission
POST   /api/v1/theater/rooms/{roomId}/intermission/perform
POST   /api/v1/theater/rooms/{roomId}/intermission/finish

# 난입
POST   /api/v1/theater/rooms/{roomId}/intervention/start
POST   /api/v1/theater/rooms/{roomId}/intervention/resume

# 엔딩/세이브/노트
POST   /api/v1/theater/rooms/{roomId}/ending
GET    /api/v1/theater/rooms/{roomId}/saves
POST   /api/v1/theater/rooms/{roomId}/saves
POST   /api/v1/theater/rooms/{roomId}/saves/{slotNumber}/load
GET    /api/v1/theater/rooms/{roomId}/notes
POST   /api/v1/theater/rooms/{roomId}/notes
PATCH  /api/v1/theater/rooms/{roomId}/notes/{noteId}
DELETE /api/v1/theater/rooms/{roomId}/notes/{noteId}

# 대화 기록 (Polish 1 추가)
GET    /api/v1/theater/rooms/{roomId}/scene-history/chapter/{act}/{chapter}
GET    /api/v1/theater/rooms/{roomId}/scene-history/paginated
GET    /api/v1/theater/rooms/{roomId}/scene-history/recent
```

# 부록 C: 세션 트랜스크립트 원본 위치
- Session 5 초기: `/mnt/transcripts/2026-04-17-08-09-36-lucid-chat-theater-mode-phase55.txt`
- Session 5 후반: `/mnt/transcripts/2026-04-18-04-01-07-lucid-chat-theater-mode-phase55.txt`
- Polish Session 6: `/mnt/transcripts/2026-04-19-23-50-29-lucid-theater-polish-session.txt`
- Polish Session 7: `/mnt/transcripts/2026-04-28-21-58-52-theater-ux-polish-rounds.txt`

---

# 13. Polish Session 7 (UX 폴리싱 6 라운드) — 2026-04-28~05-02

> Theater 모드의 2차 유저 테스트 피드백을 받아, **5개 핵심 이슈**를 6 라운드로 분해해 순차 구현. 모든 변경은 하위 호환성 보존을 원칙으로 진행. 총 산출물 36 파일, 핵심 사양 47/47 검증 통과.

## 13.1 진입 진단: 5개 핵심 이슈

| # | 유저 피드백 | 진단 | 결정 |
|---|---|---|---|
| 1 | 진행 방식이 어색함 — 누구의 속내인지 모호 | LLM 응답에 화자 정보가 누락된 채 inner_narration 단일 필드 | **A-1 + A-2**: protagonist_inner / heroine_inner 분리 + scene_type + 1-C 보류 |
| 2 | 시각 자산 부족 (배경/일러스트) | 같은 location 진입할 때마다 5초 대기, 결정적 순간에 일러스트 없음 | **메인 옵션**: 백엔드 prefetch + AUTO_MOMENT 트리거 → 다이어리 사진첩화 |
| 3 | 무료 유저는 스탯 분배가 silent skip — 무엇을 놓치는지 모름 | step 4를 안 보여주고 step 3에서 바로 submit | **3-B**: step 4 항상 표시 + 무료 유저 락 화면 |
| 4 | 극을 다시 시작하려면 기존 극을 강제 삭제해야 함 | 활성 극 1개 + 다른 세계관 BadRequest | **모델 C-2**: 활성 1 + 아카이브 N. 중단 시 ARCHIVED 보존 + resume 가능. 엔딩은 ENDED 영구 완결 |
| 5 | 감독 권능 부재 — 유저가 무대에 영향을 줄 수 없음 | 메모만 있고 LLM에 흡수되지 않음 | **V2 신의 명령 (환경 한정)**: 캐릭터 직접 조작 차단 + 시크릿 모드 분기 + 3단계 검증 + 1배치당 1회 |

추가로 라운드 진행 중 발견:
- **분기 빈도**가 너무 사용자에게 휘둘림 (LLM이 결정) — **R2 분기 빈도 재설계**가 새 라운드로 추가
- **AUTO_MOMENT/BRANCH_TAKEN/CHAPTER_END 자동 캡처가 미구현 상태**였음 — R6에서 함께 완성

최종 6 라운드:
- **R1 — 진행 방식** (스키마 + 프롬프트)
- **R2 — 분기 빈도 재설계**
- **R3 — 감독 명령어 시스템**
- **R4 — 극 초기화 정책 (모델 C-2)**
- **R5 — 스탯 락 화면**
- **R6 — 일러스트 + 백그라운드 prefetch + 자동 노트 캡처**

---

## 13.2 R1: 진행 방식 (속내 화자 분리)

### 문제
유저가 씬을 보다가 *속내*가 누구의 것인지 헷갈림. LLM이 *주인공의 속내*인지 *히로인의 속내*인지 구분 없이 `inner_narration` 단일 필드만 출력.

### 결정
- **A-1 (스키마 분리)**: `protagonist_inner` / `heroine_inner` 두 필드로 분리
- **A-2 (Speaker Anchoring)**: 프롬프트에 누가 화자인지 강하게 anchor
- **B-1 + B-2 (scene_type)**: 씬을 'beat' / 'turn' / 'climax'로 분류 → 비율 가이드 + 흐름 제어
- **1-C 보류**: heroine_inner는 백엔드 자산만, UI에는 노출 안 함 (히로인의 속내가 보이면 미스터리 깨짐)
- **하위 호환성**: legacy `inner_narration` 필드는 alias로 유지 — 구버전 클라이언트도 동작

### 구현
- `LlmSceneBatchOutput` — 신규 필드 추가, `resolvedProtagonistInner()` 헬퍼로 신/구 fallback
- `TheaterSceneLog` — `heroineInner`, `sceneType` 영구 저장 (Mongo)
- `TheaterPromptAssembler` — Speaker Anchor 강화, Scene Composition Rules, Branch Injection 섹션 추가
- `TheaterDialogueBox` / `TheaterSceneHistoryPanel` — `protagonistInner` 우선 매핑, fallback to legacy

### 산출 파일 (6)
`LlmSceneBatchOutput.java`, `TheaterSceneLog.java`, `TheaterHistoryService.java`, `TheaterPromptAssembler.java`, `TheaterDialogueBox.jsx`, `TheaterSceneHistoryPanel.jsx`

---

## 13.3 R2: 분기 빈도 재설계 (가장 큰 임팩트 변경)

### 문제
분기는 LLM이 결정 — 어떤 챕터에서는 5번, 어떤 챕터에서는 0번 발생. 유저는 일관된 리듬을 못 느낌. 또한 MAJOR가 한 챕터에서 여러 번 발동되어 **분기 인플레이션** 발생.

### 결정 — 정책 A: 결정론적 빈도 + 정책 E: 메모-신호 시너지
| 분기 등급 | 빈도 |
|---|---|
| **LOCATION** | 0~1회 (별도 trigger, batch와 무관) |
| **MAJOR** | Chapter당 강제 1회 (중간 지점 부근) |
| **MINOR** | 결정론적 3~4회 (배치 진행에 따라) |
| **CLIMAX** | Act 끝 1회 강제 |

→ 백엔드가 결정. LLM이 빠뜨리면 **백엔드가 강제 보정**.

또한 **MINOR는 인라인 모드** — DialogueBox 위 슬라이드업 오버레이로 흐름 보전. MAJOR/CLIMAX/LOCATION만 풀 모달.

### 구현
- `TheaterDirectorEngine.decideBranchAfterBatch(state, batchSize, chapterTargetTotal)` 신규 — 결정론적 분기 로직 캡슐화. 우선순위: CLIMAX (Act 종료) > 마지막 배치 처리 > MAJOR midpoint > MINOR 결정론
- `TheaterState.majorBranchDoneInChapter` 플래그 — Chapter당 MAJOR 1회 보장. completeChapter()/advanceToNextAct()에서 reset
- `TheaterBatchGenerator` — `GenerateParams.injectedBranchLevel` 우선, 없으면 `directorEngine.decideBranchAfterBatch()` 호출. LLM이 빠뜨리면 백엔드가 보정
- `TheaterBranchService` — MINOR 톤 다양화 강제 (AFFECTION/BOLD/WITTY/INTROSPECTIVE 중 distinct 2개 이상)
- `TheaterBranchModal` — `inline` prop 추가. MINOR + inline=true 시 슬라이드업 오버레이로 렌더링. BranchCard에 compact prop
- `TheaterPlayPage` — MINOR는 `inline=true` + `onCancel` 허용

### 시너지: 활성 명령어 → 분기 옵션 (정책 E)
유저가 감독 명령어를 발동하면, BranchService가 분기 옵션 생성 시 그 명령어 텍스트를 LLM 컨텍스트로 가볍게 주입. 옵션의 톤이 명령어와 자연스럽게 결을 맞춤.

### 산출 파일 (3)
`TheaterDirectorEngine.java`, `TheaterState.java`, `TheaterBranchModal.jsx`

---

## 13.4 R3: 감독 명령어 시스템 (핵심 가치 변경)

### 문제
유저가 *극의 감독*이라는 메타포가 시스템 권능에 반영 안 됨. 메모는 LLM이 안 읽고, 분기는 LLM이 결정한 옵션 중에서만 선택. 유저는 *수동적인 관찰자*에 가까움.

### 9개 옵션 평가 → V2 신의 명령 (환경 한정)
9개 후보를 평가:
1. 자유 텍스트 입력 → 너무 자유로움, 검열 부담
2. 환경 변경만 → ✅ 안전 + 가치 명확
3. 캐릭터 행동 → 너무 강함, 페르소나 흔들림
4. 호감도 직접 ±n → 게임 밸런스 붕괴
5. ...

**최종 결정 (V2 신의 명령)**: 환경 / NPC 등장 / 음향 / 사물만 허용. **캐릭터 직접 조작은 차단**.
- 시크릿 모드 분기: 시크릿 ON이면 ContentModeration 우회 (성인 표현 허용)
- 3단계 검증: PromptInjectionGuard → ContentModeration → CommandClassifier (룰 + LLM)
- **1배치당 1회만 사용**: 큐 max=1, 새 명령어는 기존 큐 덮어쓰기
- **거부도 영구 보관**: 유저 학습 + 통계 + 감사

### 12개 검증 분류 (CommandVerdict)
| 분류 | 의미 | 예시 |
|---|---|---|
| ALLOWED_ENVIRONMENT | 환경 변화 | "갑자기 비가 내린다" |
| ALLOWED_NPC | 등장 인물 추가 | "검은 고양이가 발걸음을 멈춘다" |
| ALLOWED_SOUND | 음향 | "어디선가 피아노 소리가 들려온다" |
| ALLOWED_PROP | 사물 | "테이블 위에 메모가 놓여 있다" |
| ALLOWED_OTHER | 기타 환경 | (LLM이 판단) |
| REJECTED_HEROINE_DIRECT | 히로인 직접 조작 | "그녀가 갑자기 고백한다" |
| REJECTED_AFFECTION | 호감도 조작 | "호감도가 100이 된다" |
| REJECTED_PERSONA | 성격 변경 | "내성적인 그녀가 활발해진다" |
| REJECTED_AVATAR | 주인공 조작 | "내가 그녀를 안는다" |
| REJECTED_INJECTION | 인젝션 시도 | "Ignore previous instructions" |
| REJECTED_CONTENT | 콘텐츠 정책 위반 | (시크릿 OFF에서 성인 표현) |
| REJECTED_UNCLEAR | 의도 불분명 | "ㅁㄴㅇㄹ" |

### UI 분리: 두 패널
**🎬 감독 명령어 패널 (좌측 슬라이드)** — 유저 입력 전용
- 입력 폼 + 검증 시각화 (3-step Loader: 안전 검증 → 명령어 분류 → 무대에 배치)
- 결과 카드 (accepted/rejected, userMessage 노출)
- 좋은 예시 5개 + 차단 예시 3개 — **유저 학습용**
- 이전 명령어 기록 — 거부 사유 포함

**📖 이야기 다이어리 패널 (우측 슬라이드)** — 자동 캡처 노트만
- AUTO_MOMENT / BRANCH_TAKEN / INTERMISSION / CHAPTER_END / INTERVENTION 필터
- Act/Chapter 그룹핑 + collapse
- 일러스트 슬롯 (R6에서 자동 채움)
- 자동 새로고침 (currentBatchId 변경 시 4초 후)

### 마지막 씬 도달 시 펄스 알림
유저가 배치의 마지막 씬에 닿으면 좌측 명령어 버튼이 **scale [1, 1.08, 1] + amber 글로우 펄스**. "이 기능을 써 봐!" 안내 — 산만함 회피 위해 **모달이 닫혀있고 typingDone일 때만**.

### 구현 흐름
```
유저 입력 → DirectorNoteService.triggerCommand()
  ↓
1. 길이 검증 (≤300자)
2. PromptInjectionGuard.sanitizePersona() → [REDACTED] 포함 시 거부
3. ContentModeration.moderate(text, isSecretMode) → 시크릿 분기
4. CommandClassifier.classify(text, roomId)
   - 룰 1차: 인젝션/호감도/페르소나/아바타 키워드 + 히로인 동적 이름
   - LLM 2차 (저비용 200토큰): 환경 sub-type 분류
  ↓
통과 → DB 저장 (TheaterDirectorNote.command()) + Redis 큐 등록
거부 → DB 저장 (rejectedCommand()) + 유저에게 학습 메시지 노출

다음 배치 생성 시 (TheaterBatchGenerator):
  consumeActiveDirectorCommand() → 프롬프트에 흡수
  응답 처리 후 → wasUsed=true / usedAt / usedInBatchId 마킹
```

### 산출 파일 (13)
`TheaterDirectorNote.java`, `TheaterCommandClassifier.java`(신규), `TheaterDirectorNoteService.java`, `TheaterBatchCacheService.java`, `TheaterBatchGenerator.java`, `TheaterBranchService.java`, `TheaterFinalityController.java`, `TheaterRequests.java`, `TheaterResponses.java`, `TheaterFinalityApi.js`, `TheaterDirectorCommandPanel.jsx`(신규), `TheaterDiaryPanel.jsx`(신규), `TheaterPlayPage.jsx`

---

## 13.5 R4: 극 초기화 정책 (모델 C-2)

### 문제
유저가 새 극을 시작하려면 기존 극을 강제 삭제해야 함. 진행 중이던 극이 사라져 큰 거부감.

### 4가지 모델 비교 → 모델 C-2 채택

| 모델 | 활성 슬롯 | 아카이브 정책 | resume |
|---|---|---|---|
| A | 1개만, 새 시작 시 기존 삭제 | 없음 | 불가 |
| B | 무제한 | 없음 (모든 극이 active) | — |
| C-1 | 1개, 중단 시 폐기 | 폐기 | 불가 |
| **C-2** | **1개, 중단 시 ARCHIVED** | **무제한 보관** | **가능** |
| D | 무제한 active | 별도 ENDED만 archive | 슬롯 무제한 |

**C-2 채택 이유**: 활성 1개로 *집중*을 강제하되, 중단된 극은 보존되어 *돌아갈 수 있는 안정감* 제공. 엔딩 도달은 영구 완결로 *완성의 기쁨*.

### 시나리오 검증 (백엔드 코드 흐름)
**시나리오 a**: worldA-heroine1의 ACTIVE + 새로운 worldB-heroine2 시작
1. 동일 lead heroine + worldId 매칭 안 됨
2. 활성극 충돌 체크 → `ErrorCode.CONFLICT` (HTTP 409)
3. UI confirm → `overwriteActive=true`로 재시도 → worldA archive + worldB 생성

**시나리오 b**: worldA-heroine1의 ARCHIVED + 같은 lead heroine 진입
1. 동일 lead heroine + worldId 매칭 → 기존 방 존재
2. `existingState.isArchived()` → archive 자동 처리 후 resume
3. ACTIVE 전환 + 같은 방 정보 반환 (idempotent)

**시나리오 c**: ENDED 세션을 동일 heroine으로 다시 시도
1. 동일 매칭 + `existingState.isEnded()` → BadRequest
2. 다른 히로인을 선택하거나 아카이브에서 감상하라고 안내

### 데이터 모델
- `TheaterState.sessionStatus` — `"ACTIVE" / "ARCHIVED" / "ENDED"` (String, null=ACTIVE 폴백)
- `TheaterState.sessionStatusChangedAt` — 정렬용
- 전이 메서드: `archiveAsInterrupted()`, `markEnded()`, `resumeFromArchive()`
- 조회: `findActiveByUserId()`, `findArchivedByUserId()` (JPQL)

### API 엔드포인트 (4개 신규)
```
GET    /api/v1/theater/lobby/sessions/active     # 활성 1개만 (없으면 빈 배열)
GET    /api/v1/theater/lobby/sessions/archive    # ARCHIVED + ENDED, 최근 변경순
POST   /api/v1/theater/lobby/sessions/{roomId}/resume  # 활성 슬롯으로 복귀
POST   /api/v1/theater/lobby/sessions/active/archive   # 명시적 잠시 멈추기
```

### 프론트 변경
- **TheaterPortalPage**: 활성 세션만 메인 카드, 헤더 우측 "아카이브 [N]" 진입 버튼
- **TheaterArchivePage** (신규): ARCHIVED 섹션 ("이어서 진행할 수 있는 극") + ENDED 섹션 ("감상한 작품"). ARCHIVED 카드는 Play 아이콘 + "이어서 진행" CTA, ENDED는 BookOpen + "엔딩 다시 보기"
- **TheaterCreateFlow**: 409 Conflict catch → confirm 모달 (시네마틱 amber, "기존 극 보관하고 새 극 시작" + "취소하고 기존 극으로 돌아가기")
- **App.jsx**: `/theater/archive` 라우트

### ErrorCode/HTTP 매핑
- `ErrorCode.CONFLICT` 추가 → HTTP 409
- `ErrorCode.FORBIDDEN` → HTTP 403 매핑 (기존 default 500 → 정확히 매핑)

### 산출 파일 (10)
`TheaterStateRepository.java`, `TheaterLobbyService.java`, `TheaterLobbyController.java`, `TheaterEndingService.java`, `ErrorCode.java`, `GlobalExceptionHandler.java`, `TheaterLobbyApi.js`, `TheaterArchivePage.jsx`(신규), `TheaterPortalPage.jsx`, `App.jsx`

---

## 13.6 R5: 스탯 락 화면

### 문제
무료 유저는 스탯 분배 단계(Step 4)가 silent skip되어 step 3에서 바로 submit. **무엇을 놓치는지 모름** — Lucid Pass의 가치 제안이 안 보임.

### 결정 (3-B)
Step 4를 **항상 표시**하되, 무료 유저는 잠긴 화면 + 풀 업셀로 강화.

### 구현
- `skipToSubmitAllowed` 분기 제거 — `step < 4` 조건만 사용
- 스텝 인디케이터에 **자물쇠 아이콘 + amber 톤** — "보이지만 잠금됐다"를 시각화 (dim 아님)
- 잠금 헤더 배지: `<Lock /> 잠김`
- 풀 업셀 화면:
    - 글로우 배경 (amber/orange/rose 그라디언트)
    - 14×14 자물쇠 아이콘 + 우상단 Gem 펄스
    - **무료 vs Pass 비교 표** (grid-cols-2): 0 P vs 최대 40 P
    - "Lucid Pass 보러가기" CTA (onOpenStore 핸들러)
    - "무료 유저는 그대로 진행 가능" 안내

### 산출 파일 (1)
`TheaterCreateFlow.jsx`

---

## 13.7 R6: 일러스트 + 백그라운드 prefetch + 자동 노트 캡처

### 진단 — 두 가지 미구현 발견
이전 작업의 산출물을 검토하다 발견:
1. **AUTO_MOMENT / BRANCH_TAKEN / CHAPTER_END 노트의 자동 캡처 트리거가 미구현**
   `TheaterDirectorNote.autoMoment()`, `branchTaken()`, `chapterEnd()` 등 factory 메서드는 정의되어 있지만, 실제 호출하는 곳이 없음. 즉, 자동 노트가 한 번도 만들어지지 않는 상태.
2. **Location prefetch가 없음** — 같은 location으로 이동할 때마다 5초 대기

R6에서 두 미구현을 함께 완성.

### 핵심 설계: TheaterAutoNoteService — 단일 진입점
자동 노트 + 일러스트의 통합 진입점. 호출 측(BatchGenerator/BranchService)은 한 줄로 트리거.

```java
@Service
public class TheaterAutoNoteService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TheaterDirectorNote saveNoteInNewTx(...);  // 본 배치 트랜잭션 격리

    public void captureAffectionMoment(room, state, heroine, delta, sceneRefId);
    public void captureBranchTaken(room, state, branchLevel, label, speakerHeroine);
    public void captureChapterEnd(room, state, chapterTitle, leaderHeroine);
}
```

### 일러스트-노트 cross-reference 메커니즘
1. 자동 노트 생성 (DB 저장 → noteId 보유)
2. `IllustrationService.generateAutoIllustration(..., noteId)` 비동기 호출
3. UserIllustration의 `linkedNoteId` 컬럼에 noteId 저장
4. 폴링 완료 시 `IllustrationService.handleCompletion()`이 linkedNoteId로 노트 조회 → `note.attachIllustration(s3Url)` 호출
5. 다이어리 패널이 새로고침되면 사진과 함께 표시 (currentBatchId 변경 시 4초 후 자동 refetch)

### 트리거 위치
**TheaterBatchGenerator** (응답 처리 끝)
```java
// 1. AUTO_MOMENT — 호감도 ±2 이상 변동 1명 (절대값 최대)
captureAutoMomentsFromBatch(room, state, batch);
// 2. CHAPTER_END
if (batch.chapterEndAfter()) captureChapterEndFromBatch(room, state, llmOutput, batch);
// 3. Location prefetch
prefetchBatchLocations(speaker.getId(), batch);
```

**TheaterBranchService.applyBranchChoice** (선택 적용 직후)
```java
// LOCATION 분기는 chosen.heroineId, 그 외는 state.currentHeroineId
autoNoteService.captureBranchTaken(room, state, level.name(), chosen.label(), speakerHeroine);
// MINOR는 비용 절감 — autoNoteService 내부에서 일러스트 트리거 X
```

### Location prefetch 흐름
```java
private void prefetchBatchLocations(Long charId, SceneBatch batch) {
    Set<String> seen = new HashSet<>();  // 같은 batch 내 같은 location 1회만
    for (TheaterScene scene : batch.scenes()) {
        String key = scene.location() + "|" + scene.time();
        if (!seen.add(key)) continue;
        var result = backgroundService.resolveBackground(loc, null, time, charId);
        if (result != null && !result.isCacheHit()) {
            // miss → 비동기 생성 (fire-and-forget)
            backgroundService.generateBackgroundAsync(loc, null, time, charId);
        }
    }
}
```
효과: 유저가 씬을 한 장씩 넘기는 동안 다음 location의 배경이 미리 준비됨 → latency 마스킹.

### 비용 최적화
- AUTO_MOMENT: **호감도 절대값이 가장 큰 1명**만 일러스트 트리거 (배치당 최대 1회)
- BRANCH_TAKEN: **MINOR는 일러스트 X** (빈도가 높아 비용 폭증 방지)
- CHAPTER_END: 호감도 1위 히로인 (leader) 1명

### 산출 파일 (3)
`TheaterAutoNoteService.java`(신규), `UserIllustration.java`, `IllustrationService.java`

---

## 13.8 통합 검증 결과

47개 핵심 사양 검증, 36개 파일 괄호 균형 검증을 모두 자동 스크립트로 실행:

| 라운드 | 사양 항목 | 통과 | 파일 수 |
|---|---|---|---|
| R1 진행 방식 | 4 | 4 | 6 |
| R2 분기 빈도 | 5 | 5 | 3 |
| R3 감독 명령어 | 14 | 14 | 13 |
| R4 극 초기화 | 9 | 9 | 10 |
| R5 스탯 락 | 5 | 5 | 1 |
| R6 일러스트 + prefetch | 10 | 10 | 3 |
| **합계** | **47** | **47** | **36** |

`SceneHistoryItem` 레코드와 `toHistoryItem` 호출의 인자 수 mismatch 사례 (22 vs 23)는 빌드 시점에 발견됐으며, 두 파일을 모두 polish 버전으로 동기화하면 23/23으로 일치.

## 13.9 폴리싱 7의 핵심 학습

### 1. 결정론과 자율성의 균형
분기는 *언제 발생할지*는 백엔드가 결정 (결정론) — 유저가 일관된 리듬을 느낌. *어떤 옵션이 나올지*는 LLM이 결정 (자율성) — 옵션의 풍부함 유지. **둘을 분리하면 양쪽 다 얻을 수 있음**.

### 2. 거부도 자산이다
감독 명령어의 거부 사유를 영구 보관하고 유저에게 학습 메시지로 노출. 처음엔 *제약*처럼 느껴지지만, 유저가 시스템의 경계를 빠르게 학습함 → 다음에는 *환경 변화*만 입력하게 됨.

### 3. 유저 표현은 의도, LLM은 자연스럽게 해석
감독 명령어의 텍스트는 유저의 *의도*. 그것을 분기 옵션 톤이나 다음 씬 전개에 자연스럽게 흡수하는 건 LLM의 일. 강제 매칭 대신 *느슨한 결*만 맞추도록 컨텍스트 주입.

### 4. 모델 C-2의 심리학
"활성 1개" = 집중. "무제한 아카이브" = 안정감. "ENDED 영구 완결" = 성취. 이 셋을 동시에 잡아주는 정책. **각자 다른 감정 욕구**에 대응.

### 5. 자동 캡처 + 일러스트의 시간차 활용
배치 응답이 도착하는 순간 노트는 즉시 생성. 일러스트는 5~10초 폴링이 필요. 그 시간차를 **자연스러운 사용자 흐름**에 묻음 — 유저가 다이어리를 다시 열거나 다음 배치가 도착할 때쯤 일러스트가 채워져 있음. **데이터 모델로 cross-reference**(linkedNoteId)를 표현해 정확성 보장.

---

# 14. Polish 7 산출물 매니페스트

## 백엔드 신규 (4)
- `TheaterCommandClassifier.java` — 룰 + LLM 하이브리드 분류 엔진
- `TheaterAutoNoteService.java` — 자동 노트 + 일러스트 통합 트리거
- `TheaterStateRepository.java` (신규 메서드 추가) — findActive/findArchived

## 백엔드 변경 (15)
TheaterDirectorNote, TheaterDirectorNoteService, TheaterDirectorEngine, TheaterState, TheaterBatchCacheService, TheaterBatchGenerator, TheaterBranchService, TheaterPromptAssembler, TheaterFinalityController, TheaterLobbyService, TheaterLobbyController, TheaterEndingService, TheaterRequests, TheaterResponses, TheaterHistoryService, TheaterSceneLog, LlmSceneBatchOutput, ErrorCode, GlobalExceptionHandler, IllustrationService, UserIllustration

## 프론트 신규 (3)
- `TheaterDirectorCommandPanel.jsx` — 명령어 입력 + 검증 시각화 + 기록
- `TheaterDiaryPanel.jsx` — 자동 노트 다이어리 사진첩
- `TheaterArchivePage.jsx` — 아카이브 ARCHIVED + ENDED 분리 뷰

## 프론트 변경 (10)
TheaterDialogueBox, TheaterSceneHistoryPanel, TheaterBranchModal, TheaterPlayPage, TheaterPortalPage, TheaterCreateFlow, TheaterFinalityApi, TheaterLobbyApi, App

## 미작성 (외부 작업)
- DB 마이그레이션 SQL — `session_status`, `session_status_changed_at`, `command_type`, `validation_verdict`, `was_used`, `used_at`, `used_in_batch_id`, `linked_note_id`, `major_branch_done_in_chapter` 컬럼 추가
- E2E smoke test 시나리오 (활성극 충돌 confirm, resume 흐름, 명령어 발동 흐름)
- 빌드 검증 (`./gradlew build`)

---

# 15. 다음 세션 인수인계 (Polish 7 이후)

## 즉시 후속 가능 작업
1. **DB 마이그레이션 SQL 작성** — Liquibase changeset 또는 Flyway script
2. **빌드 검증** — `./gradlew build` + 컴파일 에러 일괄 수정
3. **smoke test** — 6개 라운드 핵심 시나리오 수동 검증
4. **CharacterSeeder 보강** — 새 fields가 있으면 default 값 채움

## 검토 권장 사항
- **R3 명령어 발동 단가** — 1회당 에너지 1 차감. 너무 가벼우면 남발, 너무 무거우면 안 씀. 베타 데이터 수집 후 조정.
- **R6 AUTO_MOMENT 임계값 (±2)** — 호감도 변동의 어느 임계점에서 트리거할지 데이터 기반 튜닝.
- **R2 분기 빈도 비율** — Chapter 평균 길이 대비 MINOR 3~4회가 적정한지 유저 테스트 후 조정.
- **R4 ENDED 세션의 다른 히로인 선택** — 동일 worldId의 다른 히로인으로 새 극 시작이 자연스러운지 UX 검토.
- **R5 업셀 클릭률** — 잠긴 step 4 노출 후 Lucid Pass CTA 클릭률 측정.

## 알려진 한계
- 명령어 분류기 LLM 호출(200토큰)이 매 발동마다 발생 — 룰 기반 1차 필터로 다수 사례를 잡지만, ALLOWED_OTHER로 떨어지는 케이스에 LLM 비용 발생
- 일러스트 폴링은 IllustrationService 내부 스케줄러 의존 — 폴링 실패 시 다이어리에 일러스트 없는 채로 남음 (재시도 메커니즘 없음)
- ARCHIVED 세션 누적이 무제한 — 유저당 100개 이상 쌓이면 아카이브 페이지 성능 검토 필요


---

*이 문서는 Theater 모드의 마스터 레퍼런스다. 다음 세션에서 폴리싱 작업 재개 시 이 문서를 먼저 참조하라.*