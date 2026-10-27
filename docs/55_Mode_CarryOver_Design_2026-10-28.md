# 55. 자유 모드 → 스토리 모드 관계 이어가기(Carry-Over) 설계 + 스토리 히로인 상한 3→5

작성: 2026-10-28 KST. **상태: 구현 완료 — 미커밋 (BE·FE 각 `feature/story-carryover` 워크트리, §9).** 종원 결정 6건(§0) + 미결 ①은 "그대로 이관"으로 결정. 목업은 종원 지시로 생략하고 구현을 우선했다(개발 프리뷰로 화면 검증, §9-D).
근거 정찰: 백엔드 `ChatRoom`/`ChatRoomHeroine`/`MemoryService`/`HeroineMemoryService`/`ChatStreamServiceV2`/`StoryV2Service`/`StoryDirectorPromptAssemblerV2`, 프론트 `StoryCreateFlow`/`LobbyNewEncounterFlow`/`LobbyPage`/`ChatPageV2`, 시드 `application-characters.yml`·`application-worlds.yml` 정독.
다음 단계는 UI 목업 → 종원 컨펌 → 구현(§8). 이 문서가 본 기능의 결정 정본이다.

---

## §0. 확정 결정 (2026-10-28)

| # | 결정 | 내용 |
|---|---|---|
| 1 | **세계관 소속 제약 수용** | 스토리 히로인은 캐릭터 `worldId`와 일치해야 한다(기존 검증 유지). 이어가기는 "그 캐릭터의 소속 세계관 스토리"로만 가능. UGC 캐릭터(`storyAvailable=false`)는 대상 외. |
| 2 | **방 1개 제약 + 생성 시점 임포트** | `uk_user_world_mode`(세계관당 스토리 방 1개) 유지. 이어가기는 **새 스토리 생성 시점**에만 수행하고, 기존 방이 있으면 기존 `overwriteExisting` 경로로 덮어쓰기 confirm. 진행 중 방에 "합치기"는 v1 범위 밖. |
| 3 | **스탯 100% 승계 + 엔딩 자격 유예** | 감쇄 없음. 자유 모드에서 넘긴 호감이 엔딩 임계(±100)를 이미 넘어도 **이식 후 유저 10턴**까지는 엔딩 자격을 활성하지 않는다. 승급 자격은 즉시 허용(statusLevel도 함께 오므로 다음 단계만 열림). |
| 4 | **로그 원문 이식 + 요약 레이어 + 추가 서술** (종원 수정안) | 자유 모드 로그를 **원문 그대로** 스토리 방으로 복사한다. 길이 문제는 엔진 창(최근 20 로그, §2-C)이 자연 흡수하므로 원문은 **전량(최대 200)** 복사하고, 그보다 오래된 맥락은 **기억 요약(히로인 시점 재서술)**으로, 경계는 **브리지 서술 1건**으로 치환한다. |
| 5 | **1회 스냅샷(복사)** | 라이브 동기화 없음. 자유 모드 방은 그대로 남는다(카피에 명시). |
| 6 | **스토리 히로인 상한 3 → 5** | 백엔드 `@Size(max=3)`·프론트 상한 3곳 변경. 극장(THEATER) 상한 3은 유지. §6의 **공급 제약**(현재 어느 세계관도 스토리 히로인 3명 초과 없음)을 인지한 상태에서 결정. |

**미결 ①(§7-①) 결정(2026-10-28, 종원):** 시크릿 로그도 **게이팅 없이 그대로 이관**한다. 처리 방안은 추후 재검토(구현에 시크릿 분기 없음).

---

## §1. 왜 가능한가 — 현행 구조 근거

### 1-A. 스탯은 이미 같은 모양
- **자유(SANDBOX)**: [`ChatRoom`](../src/main/java/com/spring/aichat/domain/chat/ChatRoom.java) 컬럼에 8축(`stat_intimacy/affection/dependency/playfulness/trust` + `stat_lust/corruption/obsession`), `affection_score`(=`statAffection` 미러, `applyNormalStatChanges`에서 동기화), `status_level`, `dynamic_relation_tag`, `character_thought`, `current_bpm`, `last_emotion`.
- **스토리(STORY V2)**: [`ChatRoomHeroine`](../src/main/java/com/spring/aichat/domain/heroine/ChatRoomHeroine.java) — **동일 8축** + `status_level`(기본 STRANGER) + `dynamic_relation_tag`(기본 "낯선 사람") + `current_bpm`/`base_bpm`(65) + `character_thought` + `last_emotion`. 엔티티 주석 자체가 "V1 필드를 캐릭터별로 이전"이라고 명시. `create()`는 0 초기화, `resetProgress()`만 있고 **절대값 시드 메서드는 없음** → 신설 필요(§3-②).
- 승급: [`RelationPromotionService`](../src/main/java/com/spring/aichat/service/story/RelationPromotionService.java) `statAffection` 임계 20/40/80 → 자격 활성 → LLM 자율 발동. 페일세이프 30턴의 상수는 어셈블러 쪽(`StoryDirectorPromptAssemblerV2.PROMOTION_DEFERRED_THRESHOLD = 30`, 60행)에 있고 서비스는 `deferredTurnCount`만 증가시킨다. 엔딩: [`EndingEligibilityService`](../src/main/java/com/spring/aichat/service/story/EndingEligibilityService.java) `statAffection` ≥100 / ≤−100.

### 1-B. 기억도 같은 형식
- 자유: [`MemorySummary`](../src/main/java/com/spring/aichat/domain/memory/MemorySummary.java)(room_id, user_id, summary, turn_number). [`MemoryService.summarizeAndSaveMemory`](../src/main/java/com/spring/aichat/service/MemoryService.java)가 **유저 10턴마다**(`ChatStreamService.USER_TURN_MEMORY_CYCLE=10`) 최근 20 로그를 **한국어 3문장**("유저 정보 + 관계 진전" 중심, 분석형)으로 요약.
- 스토리: [`HeroineMemorySummary`](../src/main/java/com/spring/aichat/domain/memory/HeroineMemorySummary.java)(room_id, **character_id**, user_id, summary, turn_number, **source_mode**). 디렉터 응답의 `memory_delta.by_character`를 누적, 10메시지마다 **히로인 시점 1~3문장**으로 압축. 프롬프트 [8] CUMULATIVE MEMORY가 캐릭터별 **전량** 주입(`findByRoomIdAndCharacterIdOrderByCreatedAtAsc`).
- `source_mode` 컬럼이 이미 있어 출처 `"SANDBOX_IMPORT"` 표기가 자연스럽다. 스키마 변경 없음.

### 1-C. 로그는 두 모드가 같은 컬렉션·같은 스키마
- Mongo `chat_logs` 단일 컬렉션([`ChatLogDocument`](../src/main/java/com/spring/aichat/domain/chat/ChatLogDocument.java)): roomId, role, rawContent, cleanContent, emotionTag, innerThought, thoughtUnlocked, hidden, **scenesJson**, dialogueOptionsJson, createdAt. V1 스트림도 `scenesJson`을 저장한다(`ChatStreamService` 1137행).
- **LLM 히스토리 창: V1·V2 모두 `findTop20ByRoomIdOrderByCreatedAtDesc` = 최근 20 로그(유저 10턴).** 그보다 오래된 로그는 기억 요약으로만 LLM에 닿는다.
- UI 복원: `/api/v1/chat/rooms/{id}/logs?page&size=50`(hidden 제외) **두 모드 공용**. `ChatPageV2.expandLogWithScenes`(프론트 `src/pages/ChatPageV2.jsx`)는 V2 모드에서 시스템 씬을 **speaker만으로** 판정한다(`isSystemSpeakerName(scene.speaker)`, 785행 — 주석은 `scene.isSystem` 우선이라 적혀 있지만 코드는 읽지 않음) → **V1 scenesJson 호환**(히로인 이름 일치 → ASSISTANT, speaker null → SYSTEM pill). 씬의 `location/outfit` enum은 복원기가 읽지 않으므로 세계관 불일치가 화면에 노출되지 않는다.

### 1-D. 디렉터는 관계 단계를 이미 읽는다
[`StoryDirectorPromptAssemblerV2`](../src/main/java/com/spring/aichat/service/prompt/StoryDirectorPromptAssemblerV2.java) [4-marker] CURRENT SPEAKER가 `statusLevel`·`dynamicRelationTag`·스탯을 주입. 값만 심으면 "연인으로 시작하는 스토리"는 프롬프트 수정 없이도 어느 정도 작동한다. 단 [OPENING] 지시(148~150행)는 "유저는 아직 아무 행동도 하지 않았다 / 첫 장면"만 전제 → **재회 변형 필요**(§3-⑥).

---

## §2. 이식 데이터 계약

### 2-A. 요청
`CreateStoryV2Request`(기존)에 additive 필드:
```
carryOvers: [ { characterId: Long, sandboxRoomId: Long, includeLogs: boolean=true } ]   // 선택, 히로인별 최대 1건
```
검증(서비스): `sandboxRoom.user == 요청 유저`, `chatMode == SANDBOX`, `sandboxRoom.character.id == characterId`, `characterId ∈ heroineIds`. 위반 시 400. 자유 모드 방이 `endingReached`여도 허용(엔딩 상태는 이식하지 않음 — 스토리는 자체 엔딩 엔진).

### 2-B. 스탯 스냅샷 (히로인별)
`ChatRoomHeroine.seedFromSandbox(ChatRoom sandbox)` 신설:
8축 그대로(clamp ±100), `statusLevel`, `dynamicRelationTag`, `characterThought`(+`thoughtUpdatedAtTurn=0`), `lastEmotion`, `currentBpm`, **`baseBpm = RelationStatusPolicy.calculateBaseBpm(statAffection)`**. `lastSpokenAt`/`lastIllustrationHint`는 비움.

### 2-C. 로그 — "원문 전량 + 엔진 창 20 + 요약 레이어" (결정 4)
종원 수정안("원문 이식, 너무 길면 요약으로 치환")을 엔진 실측에 맞춰 다음으로 확정한다.

| 층 | 무엇을 | 왜 |
|---|---|---|
| **원문 로그** | 자유 모드 로그 **전량**(최신 200건 캡, `findTop200ByRoomIdOrderByCreatedAtDesc` 재사용) → `roomId=스토리방`으로 복사. `createdAt` **원본 유지**(정렬상 스토리 로그보다 앞), `rating/dislikeReason/audioUrl` null, `hidden=true` 로그 제외, 신규 필드 `importedFromRoomId` 세팅 | 유저 체감 연속성(스크롤백에 그대로 보임). LLM은 어차피 **최근 20건만** 읽으므로 길이가 프롬프트 비용을 늘리지 않는다 → "임계값 초과 시 치환"이 **엔진에 의해 자동으로** 일어난다. |
| **요약 레이어** | 자유 방 `MemorySummary` 전량 → LLM 1회 **재서술**(히로인 시점·1~3문장·장소/시대 디테일 추상화·감정/사건/유저정보 보존) → `HeroineMemorySummary(source_mode="SANDBOX_IMPORT", turn_number=0)` 요약 1행당 1행. `MemorySummary`가 0건(유저 10턴 미만)이면 최근 20 로그로 1회 요약 생성. LLM 실패 폴백: 원문 요약 그대로 저장 | 20건 밖의 맥락이 LLM에 닿는 **유일한 경로**. 자유 요약(분석형 3인칭)과 스토리 기억(히로인 시점)의 톤 차이를 여기서 흡수. |
| **브리지 서술** | 가시 `SYSTEM` 로그 1건(`ChatLogDocument.system`) — 예: "서로를 알아 온 시간이 이 세계로 이어진다." 재서술 호출에서 함께 생성, 실패 시 템플릿 | UI 구분선 역할 + LLM 히스토리에 "여기부터 스토리"라는 경계 신호(SYSTEM role은 히스토리 빌더가 system 메시지로 전달). |

커버리지: 요약은 유저 10턴(=20 로그) 주기이고 원문은 최근 20건 이상이 항상 복사되므로, 마지막 요약 이후 미요약 꼬리(<20 로그)는 원문 창이 덮는다. 공백 없음.

### 2-D. 오프닝·RAG·자격 상호작용 (반드시 수정)
- **오프닝 멱등 가드 충돌**: [`ChatStreamServiceV2.generateOpeningStream`](../src/main/java/com/spring/aichat/service/story/ChatStreamServiceV2.java)(304행)이 `countByRoomId > 0`이면 오프닝을 **건너뛴다**. 로그를 이식하면 오프닝이 생성되지 않음 → 가드를 "`importedFromRoomId == null`인 로그 0건"으로 변경(레포 메서드 추가).
- RAG 임계(`RAG_SKIP_THRESHOLD=6` 로그): 이식 직후부터 월드 메모리 조회 활성 — 의도된 효과(스토리 방 `MemorySummary`는 아직 없으니 빈 문자열, 히로인 기억은 [8]에서 항상 주입).
- 엔딩 유예(결정 3): `ChatRoom`에 `carry_over_at`(nullable) 추가 → `EndingEligibilityService.checkAndActivateEligibility`에서 `carryOverAt != null && 이식 후 유저 턴 < 10`이면 return false. 유저 턴 수는 로그 카운트에서 `importedFromRoomId == null && role == USER`로 계산.

### 2-E. 시크릿 콘텐츠 (미결 ① — 추천안)
V2 `resolveSecretMode` = `room.secretModeActive && world.secretAllowed && 유저 권한`. 세계관 4종 중 **현대 한국만 `secret-allowed: false`**(taeri/luna/chaerin). 자유 방이 시크릿 활성 상태이고 대상 세계관이 비허용이면:
- **추천**: 원문 로그 미이식(`includeLogs` 강제 false) + 요약 레이어·브리지만 이식, 재서술 프롬프트에 "비노출 수위" 지시. 사유: 로그엔 시크릿 플래그가 없어 선별 불가, 디렉터가 히스토리 톤을 이어받을 위험.
- 대안: 전량 이식(유저가 이미 본 콘텐츠). 세계관 게이트가 생성 측 정책이라는 점에서 방어 가능하나 추천하지 않음.

### 2-F. 페르소나·닉네임
자유 방 `userPersona`(캐릭터별)가 있으면 StoryCreateFlow step3 자유 입력 **프리필**(단 `hasFreePersonaUnlock` 게이트 존중 — 미보유면 프리셋 유지 + 안내). 닉네임은 `User.nickname` 폴백 그대로.

---

## §3. 백엔드 변경 범위 (좌표)

| # | 위치 | 변경 |
|---|---|---|
| ① | `dto/story/StoryV2Requests.CreateStoryV2Request` | `carryOvers` additive 필드(§2-A). `heroineIds` `@Size(max=5)`(§6) |
| ② | `domain/heroine/ChatRoomHeroine` | `seedFromSandbox(ChatRoom)` 신설(§2-B) |
| ③ | `service/story/StoryCarryOverService` **신설** | 검증 → 스탯 시드 → 로그 복사 → 요약 재서술(동기, `props.sentimentModel()`급 저가 모델) → 브리지 로그. `StoryV2Service.createOrReuseRoom`의 `reconfigureHeroinesAndPresences` 직후 호출(신규·덮어쓰기 양 경로). 로그/요약 쓰기는 방 생성 TX 안, LLM 호출 실패는 폴백으로 흡수(방 생성 실패로 번지지 않게) |
| ④ | `domain/chat/ChatLogDocument` + `ChatLogMongoRepository` | `importedFromRoomId`(Long, nullable) 필드. `countByRoomIdAndImportedFromRoomIdIsNull`, `countByRoomIdAndRoleAndImportedFromRoomIdIsNull` 추가. Mongo라 마이그레이션 없음 |
| ⑤ | `dto/chat/ChatLogResponse` | `imported: boolean` additive(UI 구분선용). 하위 호환 생성자 유지 |
| ⑥ | `service/prompt/StoryDirectorPromptAssemblerV2` | `openingMode && carriedHeroineIds 비어있지 않음` → `[OPENING-REUNION]` 변형: "이 인물(들)과 유저는 이미 {관계단계}다. 첫 만남이 아니라 **재회**로 열어라. [8] 누적 기억을 1~2개 자연스럽게 환기하되 나열하지 마라." 상태 변경 금지 제약은 동일 |
| ⑦ | `service/story/ChatStreamServiceV2.generateOpeningStream` | 멱등 가드를 ④의 non-imported 카운트로 교체(§2-D) |
| ⑧ | `domain/chat/ChatRoom` + Flyway 1건 | `carry_over_at TIMESTAMP NULL`(유예 판정·추적). 버전 번호는 적용 시점의 최신 V를 확인해 부여 |
| ⑨ | `service/story/EndingEligibilityService` | 이식 후 유저 10턴 유예(§2-D) |
| ⑩ | `service/story/StoryV2Service.getCreateContext` + `dto/story/StoryV2Responses.WorldHeroineCardResponse` | 히로인별 `sandboxRelation {roomId, statusLevel, dynamicRelationTag, affectionScore, logCount, lastActiveAt, secretModeActive}` additive(없으면 null). `chatRoomRepository.findByUser_IdAndCharacter_IdAndChatMode(user, char, SANDBOX)` |
| ⑪ | `LobbyService.toCharacterResponse` | 이미 `worldId` 포함 — 기억의 끈 카드에서 대상 세계관 결정에 사용. 변경 없음 |

에너지: 이식 **무과금**. 재서술 LLM 1회는 운영 비용만.

---

## §4. 프론트 변경 범위 (좌표)

| # | 위치 | 변경 |
|---|---|---|
| ① | `components/story-v2/StoryCreateFlow.jsx` Step2 카드 | `sandboxRelation` 있으면 뱃지("자유 모드 · 친구 · 호감 54 · 대화 N건") + **"관계 이어가기" 토글(기본 ON)**. 선택 시 `carryOvers`에 축적. 상한 3→5 반영(14/71/102/108~110/348행) |
| ② | 같은 파일 Step3 | `presetHeroineIds` 경로(통합 로비에서 step3부터 시작)엔 Step2가 없으므로 **"이어갈 관계" 요약 카드 + 토글**을 Step3 상단에 삽입. 자유 방 `userPersona` 프리필(§2-F). 비허용 세계관×시크릿 활성이면 "대화 원문은 가져오지 않고 기억만 이어갑니다" 안내(§2-E) |
| ③ | `components/lobby/LobbyNewEncounterFlow.jsx` 148행 | `maxSelect`를 모드별 분리: SANDBOX 1 / **STORY 5** / THEATER 3(극장 백엔드 `TheaterRequests` `max=3` 유지). 캐릭터 카드에 자유 모드 관계 뱃지(`/lobby/rooms` 결과 매칭) |
| ④ | `pages/LobbyPage.jsx` 기억의 끈 `DialogueRoomCard` | 2차 액션 **"스토리로 이어가기"**(캐릭터 `worldId`·`storyAvailable` 있을 때만) → `setStoryV2CreateFlow({ worldId, presetHeroineIds:[characterId], carryOverSeed:{characterId, sandboxRoomId} })` |
| ⑤ | `pages/ChatPageV2.jsx` | `log.imported` 전환 지점에 구분선 pill("자유 모드에서 이어진 기록"). 복원기 변경 없음(§1-C 호환) |
| ⑥ | `pages/ChatPage.jsx`(자유) 설정 메뉴 | "이 관계를 스토리로" 진입점 — **v1.1**(선택) |

카피 원칙: "복사"임을 명시 — "자유 모드 기록은 그대로 남아요." 덮어쓰기 confirm은 기존 `ConflictModal` 재사용(문구에 "이어갈 관계도 함께 적용됩니다" 추가).

---

## §5. 이식 흐름 (시퀀스)

```
[로비] 기억의 끈 자유 방 카드 "스토리로 이어가기"  ─┐
[로비] 새로운 만남 → 스토리 → 히로인 선택(뱃지/토글) ┴→ StoryCreateFlow(step3) ──POST /v2/story/rooms {heroineIds, carryOvers, persona…}
   409 STORY_V2_ROOM_EXISTS → ConflictModal → overwriteExisting=true 재호출
→ StoryV2Service.createOrReuseRoom
   ├ (덮어쓰기면 cascadeResetRoom) → createStoryV2 / reconfigureHeroinesAndPresences (ChatRoomHeroine 0 초기화)
   └ StoryCarryOverService.apply(room, carryOvers)
       ① 검증  ② seedFromSandbox  ③ 로그 복사(≤200, importedFromRoomId)  ④ MemorySummary → 재서술 → HeroineMemorySummary(SANDBOX_IMPORT)
       ⑤ 브리지 SYSTEM 로그  ⑥ room.carryOverAt = now
→ 프론트 navigate /v2/chat/:roomId → GET /logs (이식 로그 + 브리지 표시) → POST /opening/stream
   가드: non-imported 로그 0건 → 생성 (openingMode + [OPENING-REUNION])
```

---

## §6. 스토리 히로인 상한 3 → 5 (결정 6)

### 6-A. 상한이 박힌 곳 (전부)
| 레이어 | 좌표 | 현재 |
|---|---|---|
| BE | `dto/story/StoryV2Requests.java:31` | `@Size(min=1, max=3)` → **5** |
| FE | `components/story-v2/StoryCreateFlow.jsx` 14·71·102·108~110·348행 | 주석 2곳·`canProceedStep2`·`toggleHeroine` 상한·카피 "1~3명" → **5** |
| FE | `components/lobby/LobbyNewEncounterFlow.jsx:148` | `maxSelect = SANDBOX ? 1 : 3` — **STORY/THEATER 분리 필수**(극장은 3 유지) |
| BE(유지) | `dto/theater/TheaterRequests.java:32` | 극장 `max=3` **변경 없음** |

프롬프트·라우팅·메모리는 N을 가정하지 않는다(`buildSection4SpeakersDefinition`이 히로인 리스트를 순회, `initializePresences`도 리스트 순회). `ChatPageV2`의 `CharacterDisplay`는 **현재 화자 1인**만 그리므로 스프라이트 레이아웃 문제 없음. `StoryV2CharacterPanel`은 `flex-wrap`.

### 6-B. ⚠ 공급 제약 — 지금 올려도 체감 변화 없음
운영 프로필 `prod,characters,worlds,v2`([06_Deploy_Runbook](06_Deploy_Runbook.md))가 로드하는 `application-characters.yml` 10명의 세계관 분포:

| 세계관 | 스토리 가능 히로인 | 인원 |
|---|---|---|
| MEDIEVAL_FANTASY | airi, claire | 2 |
| ORIENTAL_FANTASY | yeonhwa, seolah | 2 |
| MODERN_KOREA | taeri, luna, chaerin | 3 |
| FANTASY_ACADEMY | rosetta, sierra, edel | 3 |

**어느 세계관도 3명을 넘지 않는다.** 상한 5는 다음 중 하나가 와야 의미가 생긴다:
- (a) **공식 캐릭터 추가**(콘텐츠 로드맵) — 추천. 상한 변경은 선행 준비로 지금 해 둔다.
- (b) 교차 세계관 캐스팅 — `createOrReuseRoom`의 "does not belong to world" 검증 완화만으로는 안 된다. `initializePresences`가 `CharacterRoutine.locationKey`(소속 세계관의 `WorldLocation` 키)로 배치하므로 타 세계관에선 루틴이 전부 비매칭 → 유저 위치로 몰림 + 디렉터 프롬프트의 세계 설정과 캐릭터 배경 충돌. **본 범위 밖**.
- (c) UGC 캐릭터 스토리 개방 — `Character.createUgc`가 `storyAvailable=false` 고정(v1.1 예정 주석). 루틴 데이터·모더레이션 전제. **본 범위 밖**.

### 6-C. 품질·비용 리스크 (5명 시)
- 정적 프롬프트 +2 히로인 블록(캐시 ephemeral이라 턴당 비용 영향 작음). 시드 1명분 yml ≈ 14K chars 중 프롬프트 투입분 추정 3~5K 토큰/명.
- 같은 장소에 4~5명이 모이면 씬이 분산된다(멀티 씬 4~5개 안에서 화자 분배). 오프스크린 알림(`OffscreenNotificationService`, 친밀 ≥30·24h 쿨다운)도 히로인 수에 비례해 늘어남.
- UX 가드 제안: 4명째 선택 시 "인원이 많으면 이야기가 분산될 수 있어요" 힌트(차단 아님).

---

## §7. 리스크 · 미결

| # | 항목 | 상태/완화 |
|---|---|---|
| ① | **시크릿 로그 × 비허용 세계관**(§2-E) | **미결 — 종원 결정 필요.** 추천: 요약만 이식 |
| ② | 톤 충돌(자유 1:1 화법 → 디렉터 시점 세계 탐험) | 브리지 서술 + [OPENING-REUNION] + 재서술 요약으로 완화. **실측 필수**(첫 구현 후 2~3 세계관 샘플) |
| ③ | 자유 모드 장소/시간 enum이 세계관과 불일치(카페 vs 중세) | 복원기가 씬 `location/outfit`을 읽지 않아 UI 비노출. LLM엔 대사 원문만 전달. 재서술 프롬프트가 장소 디테일 추상화 |
| ④ | 엔딩 조기 발동 | 유저 10턴 유예(§2-D) |
| ⑤ | 세계관당 방 1개 → 덮어쓰기 | 기존 ConflictModal 재사용. 대시보드 10/18 기록대로 overwrite 경로는 프로드에서 최근 실사용 이력이 없다 → 이어가기 구현 시 **덮어쓰기+이식 조합을 첫 검증 항목**으로 |
| ⑥ | Mongo 중복 저장 | 최신 200건 캡. 자유 방 평균 로그 수 대비 무해 |
| ⑦ | 부분 실패 | 스탯·로그·요약 쓰기는 생성 TX 내. 재서술 LLM 실패만 폴백(원문 요약 저장). 롤백 시 방도 미생성 → 유저는 재시도 |
| ⑧ | 역방향(스토리 → 자유) 수요 | 리텐션 루프로는 더 강할 수 있음. v1은 단방향, 데이터 계약은 대칭(`seedFromSandbox`의 역함수 가능) — **v2 후보** |
| ⑨ | 저가 파밍(자유 1E로 스탯 올려 스토리 2E 진입) | 스탯은 페이월이 아니라 실질 우회 아님. 모니터링만 |

---

## §8. 다음 단계 · 검증 계획

1. **UI 목업**(코드 전): StoryCreateFlow Step2 뱃지/토글 · Step3 "이어갈 관계" 카드 · 기억의 끈 카드 2차 액션 · ChatPageV2 구분선 pill. 다관점 크리틱(초심자 이해도/시각 시스템/카피/반응형) 후 종원 컨펌.
2. **구현 순서**: BE ③④⑦(이식 파이프라인+오프닝 가드) → ②⑧⑨(스탯·유예) → ⑥(재회 디렉티브) → ①⑩(DTO) → FE ①~⑤ → 상한 5(§6-A 4곳).
3. **검증**: (a) 단위: `seedFromSandbox` 클램프·baseBpm, 유예 판정. (b) 통합: carryOver 생성 → 히로인 스탯 = 자유 방 스탯, 로그 수 = min(원본,200)+1, `HeroineMemorySummary(SANDBOX_IMPORT)` ≥1, 오프닝 **정확히 1회** 생성, 덮어쓰기 경로 동일. (c) 프론트 `npm run build` + 375/320px 실화면. (d) 실측: 3세계관 샘플에서 재회 오프닝·기억 환기 품질 — 에이전트 환경은 백엔드 기동 불가(IntelliJ 시크릿)라 **종원 로컬/프로드에서 수행**.
4. 큰 변경 뒤 새 컨텍스트 독립 검토(전역 규약).

---

독립 검토(2026-10-28, 새 컨텍스트 에이전트): 사실관계 15항목 전부 코드와 일치. 정밀 보정 4건(페일세이프 상수 위치·`MemorySummary.user_id`·복원기 speaker-only 판정·StoryCreateFlow 102행) 반영 완료.

---

## §9. 구현 기록 (2026-10-28 · 미커밋)

### 9-A. ⚠ 브랜치 함정 — §1~§6의 좌표는 `feature/diorama` 트리 기준이었다
정찰 당시 두 리포의 메인 체크아웃이 모두 **`feature/diorama`**(docs/08: master 머지 금지 격리 브랜치, master보다 BE 169·FE 61 커밋 뒤)였다. 로비 코드베이스가 아예 다르다(디오라마 트리 `pages/LobbyPage.jsx`+`LobbyNewEncounterFlow` vs **master `pages/lobby/{LobbyShell,HomeTab,StoryTab,ArchiveTab}`**). 따라서 구현은 **master 기반 워크트리**에서 했다:

| 리포 | 워크트리 | 브랜치 | 베이스 |
|---|---|---|---|
| BE | `C:\Users\zapza\Desktop\MuseLab\aichat-carryover` | `feature/story-carryover` | master `318b02c` |
| FE | `C:\Users\zapza\Desktop\LucidChat-Front\LucidChat-Front-carryover` | `feature/story-carryover` | master `33c6163` |

§3/§4/§6의 행 번호는 master와 다르다. master 앵커: `ChatStreamServiceV2.generateOpeningStream` 392행(가드 407), `StoryDirectorPromptAssemblerV2.buildOpeningDirective` 149행, `StoryV2Service.createOrReuseRoom` 464행·`createOrReuseUgcRoom` 552행, `StoryCreateFlow.jsx`는 3단계(세계관/캐릭터/내 프로필 — 페르소나 피커는 블록 B에서 사장).

### 9-B. 설계 대비 달라진 점 (master 실측)
- **BPM 미이식**: master `ChatRoomHeroine`엔 `currentBpm/baseBpm`이 없다(제거됨) → §2-B의 baseBpm 항목 폐기.
- **페르소나 프리필(§2-F) 폐기**: master는 `applyProfileSnapshot`이 현재 프로필을 자동 스냅샷(피커 없음).
- **UGC 월드 스토리도 동일 경로**: `createOrReuseUgcRoom`에도 validate/apply를 넣었다(UGC 캐릭터의 자유 방도 같은 `ChatRoom` 테이블). 단 보관함 진입점은 공식 세계관(`CharacterResponse.worldId`) 캐릭터만 — UGC 월드 ref(`UGCW_`)는 후속.
- **오프닝 가드는 프론트에도 있었다**: `ChatPageV2`가 `logs.length === 0`으로 오프닝 필요를 판정하고 자동 발사 효과도 `messages.length > 0`이면 중단 → 둘 다 `imported` 축으로 교정(§9-C).
- **`@CreatedDate` 감사와의 충돌**: Mongo 감사는 새 문서(id==null)의 createdAt을 무조건 now로 덮는다 → 복사본·브리지에 ObjectId를 **선지정**해 isNew=false로 만들어 원본 시각을 보존(`ChatLogDocument.copyForCarryOver/carryOverBridge`).
- **브리지도 이식 배치**: `importedFromRoomId`를 찍어 오프닝 가드·유저 턴 카운트에서 함께 제외(안 그러면 브리지 1건이 "이 방의 로그"로 세어져 오프닝이 영영 안 뜬다).
- **엔딩 유예는 현재 휴면**: master `legacy.ending.dialogueEnabled=false`(블록 D)라 V2 엔딩 자격 자체가 꺼져 있다. 유예 로직은 게이트가 켜질 때를 위해 구현·테스트했다.

### 9-C. 변경 파일
**BE** (`aichat-carryover`): `domain/chat/ChatRoom`(carry_over_at·markCarriedOver·reset) · `domain/heroine/ChatRoomHeroine`(carried_from_room_id·seedFromSandbox·isCarriedOver) · `domain/chat/ChatLogDocument`(importedFromRoomId·copyForCarryOver·carryOverBridge) · `domain/chat/ChatLogMongoRepository`(IsNull 카운트 2종) · `domain/memory/HeroineMemorySummary.forSandboxImport` · `dto/chat/ChatLogResponse.imported` · `controller/ChatController.toDto` · `dto/story/StoryV2Requests`(carryOvers·CarryOverRequest·max 5) · `dto/story/StoryV2Responses`(SandboxRelationResponse) · **신규 `service/story/StoryCarryOverService`** · `service/story/StoryV2Service`(validate/apply 4경로·sandboxRelationFor) · `service/story/HeroineMemoryService.importSummaries` · `service/story/ChatStreamServiceV2`(오프닝 가드) · `service/prompt/StoryDirectorPromptAssemblerV2`([OPENING-REUNION]) · `service/story/EndingEligibilityService`(유예) · **`db/migration/V40__story_carry_over.sql`** · 테스트 `StoryCarryOverServiceTest`(11)·`EndingEligibilityServiceCarryOverTest`(3).
**FE** (`LucidChat-Front-carryover`): `components/story-v2/StoryCreateFlow.jsx`(상한 5·관계 뱃지/토글·3단계 이어갈 관계 요약·carryOverSeed·ConflictModal 문구) · `pages/lobby/StoryTab.jsx`(router state `carryOver` → CreateFlow) · `pages/lobby/ArchiveTab.jsx`(자유 방 행 아래 "이 관계를 스토리로 이어가기") · `pages/ChatPageV2.jsx`(needsOpening/자동 발사 `imported` 축·씬 전파·구분선) · `api/StoryV2Api.js`(JSDoc) · `dev/SecondaryDesignPreview.jsx`(픽스처) · 외부 `LucidChat-Front/.claude/launch.json`에 `lucid-front-carryover` 설정 추가.

### 9-D. 검증 결과
| 항목 | 결과 |
|---|---|
| BE `compileJava`/`compileTestJava` | 통과 |
| `StoryCarryOverServiceTest` / `EndingEligibilityServiceCarryOverTest` | 12/12 · 3/3 통과(검토 반영 후 재실행) · 인접 `ChatStreamServiceV2CompensationTest` 6/6 |
| `StoryDirectorPromptAssemblerContractTest` | 7/8 — 실패 1건은 `preservesEveryOriginalFieldDescriptionAndCriticalRule`의 **CRLF vs LF**(이 PC `core.autocrlf=true`, 비교 블록은 본 변경과 무관한 Critical Rules 텍스트). 오프닝 관련 `ambientAndOpeningDoNotInstructInventingTheUsersInterior`는 통과 |
| FE `npm run build` · `check:tdz/entry/tts/voice-media` | 전부 통과(check:entry는 오프닝 자동 발사 효과를 실제 실행해 `messages:[{}]`→0회·빈 방→1회 검증) |
| FE lint | 변경 파일 신규 위반 0. `StoryCreateFlow` 'motion' 미사용 1건과 `ChatPageV2` 20/13은 master 동일 베이스라인 |
| 화면(개발 프리뷰 `/__design/secondary`, 백엔드 불필요) | 2단계 카드 관계 행("자유 모드 · 썸 · 대화 37건" + "관계 이어가기" 스위치 ON/OFF)·4명+ 힌트 조건·3단계 "자유 모드에서 이어갈 관계" 요약, 데스크톱 1280·모바일 375 확인 |
| 독립 코드 검토(새 컨텍스트) | §9-E |

**미검증(백엔드 기동 불가 — IntelliJ 시크릿):** 실제 방 생성→이식→오프닝(재회) 1회 생성→구분선 표시, 덮어쓰기+이식 조합, 보관함 진입점→StoryTab state 인계, Flyway V40 적용(`ddl-auto=validate`), 재서술 LLM 품질. **종원 로컬/프로드 실측 항목.**

### 9-E. 독립 검토 반영 (새 컨텍스트 에이전트, 2026-10-28)
검토 판정: "차단·데이터 손상 결함 없음, 파이프라인·가드·DTO 배관은 끝까지 정합". 지적 6건 중 5건 반영, 1건 수용.

| # | 지적 | 조치 |
|---|---|---|
| 1 | 복수 히로인 이식 시 브리지가 계획별 '그 방 마지막 로그+1초'라 다른 방 로그 한가운데 박힘 | **반영** — 브리지 앵커를 전체 이식 로그의 최댓값으로, 계획 순서대로 1초 간격. 테스트 `apply_multiHeroine_bridgesAfterAllImportedLogs` 추가 |
| 2 | Mongo 쓰기가 TX 밖 — 덮어쓰기 경로에서 롤백되면 "이식 로그만 있고 이 방 로그 0건" 반쪽 상태 | **반영** — 로그 복사·브리지를 `TransactionSynchronization.afterCommit`으로 지연(비TX 호출/테스트는 즉시). 커밋 후 실패는 로그만 |
| 3 | 유예 카운트가 USER 로그만 세서 액션 턴(hidden SYSTEM `[ACTION:…]`)이 빠짐 | **반영** — 이 방 ASSISTANT 로그 수(턴당 1건, 오프닝 포함)로 변경, 상수 `CARRY_OVER_GRACE_TURNS=10` |
| 4 | 200 캡이 hidden 포함 집계 | **반영** — `findTop200ByRoomIdAndHiddenNotOrderByCreatedAtDesc` 신설 |
| 5 | 이식 로그가 V2 턴 카운터(`countByRoomId`)를 N만큼 밀어 `HeroineMemorySummary.turnNumber`가 오프셋 | **수용** — 기능 영향 없음(요약 주기는 위상만 이동). 턴 번호가 통계에 쓰이게 되면 non-imported 카운트로 교체 |
| 6 | 보관함 버튼은 모든 자유 방에 뜨는데 가시 로그 0건이면 CreateFlow에서 조용히 '새로 시작' | **반영** — 시드 캐릭터인데 관계가 없으면 카드 아래 "아직 나눈 대화가 없어 이어갈 기록이 없어요. 새 관계로 시작해요." 안내 |

수용 리스크(검토 기록): 생성 TX 안에서 히로인 수만큼 동기 LLM 호출(커넥션 점유·`uk_user_world_mode` 삽입 창), 공식 경로엔 UGC 경로의 `DataIntegrityViolation→409` 매핑이 없어 LLM 창 동안 더블 서밋이 500 — 기존 구조의 문제라 이번 범위 밖.

출처: 본문 링크의 소스 파일 직접 정독(2026-10-28), `docs/06_Deploy_Runbook.md`(운영 프로필). 대시보드가 링크하는 `docs/54_Story_Room_Restart_2026-10-18.md`는 `docs/`에 **부재**(링크 깨짐) — 10/18 결정은 대시보드 요약으로만 참조했다.
