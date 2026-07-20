# Phase 6 — 도그푸딩 결과 보고서

> 공동 개발자가 직접 유저 입장에서 서비스를 플레이하며 식별한 UX 문제점 3건. 각 문제에 대해 코드 베이스 진단 + 단계별 개선 방안 정리.

---

## 0. 발견된 문제 요약

| # | 영역 | 문제 | 심각도 | 출시 전 처리 |
|---|------|------|--------|-------------|
| 1 | 디렉터 인터루드 UX | 비동기 깜짝 이벤트가 몰입 저해 | 높음 | ✅ 즉시 처리 (30분) |
| 2 | Theater 멀티 히로인 | Chapter/Act 전환 시 맥락 단절 + 기억 초기화 | 높음 | ✅ 결함 B 즉시, 결함 A는 단계적 |
| 3 | 캐릭터 영혼 빈약 | "유저바라기" 성향, 캐릭터 자체 정체성 부족 | 핵심 가치 | ✅ Tier 1~2 처리 (0.5~1일) |

---

## 문제 1 — 디렉터 인터루드 UX 저하

### 1.1. 사용자 보고 현상

> "스토리 모드에 비동기로 참여하는 디렉터 엔진은, 대화가 끝나지 않은 상황에도 가끔씩 깜짝 이벤트로 인터루드를 발생시킨다. 중요한 대화에 몰입을 하고 있는데 뜬금없이 생뚱맞은 이벤트가 발생하고, UX의 상승은 커녕 저하로 이어졌다."

### 1.2. 트리거 흐름 분석

**호출 위치**: `ChatStreamService.java:1234-1237`
```java
// triggerPostProcessing 내부 — 모든 USER 턴 후처리에서 비동기 발동
if (ChatModePolicy.supportsDirectorMode(chatMode)) {
    directorService.evaluateAndCache(roomId, userMsgCount);
}
```

**가드 조건**: `DirectorService.evaluateAndCache` (라인 56-92)
```java
if (!ChatModePolicy.supportsDirectorMode(room.getChatMode())) return;
if (room.isEventActive() || room.isPromotionPending() || room.isPromotionWaitingForTopic()) return;
if (room.isEndingReached()) return;

int turnsSince = getTurnsSinceLastIntervention(roomId, currentTurnCount);
if (turnsSince < MIN_INTERVENTION_GAP) return;  // MIN_INTERVENTION_GAP = 3
if (hasDirective(roomId)) return;
```

### 1.3. 진단 — 구조적 결함

**근본 원인**: 가드 조건이 모두 *백엔드 상태*만 본다. 유저가 *지금 깊은 대화에 빠져있는지*는 시스템에 전달되지 않는다.

**3가지 구체적 결함**:
1. **`MIN_INTERVENTION_GAP=3`이 너무 짧다**: 단 3턴 갭만 두면 발동 가능.
2. **`topic_concluded` 보수성 부족**: LLM이 항상 정확히 판단하지 못함. 대화 중간에도 `true`로 잘못 판정 → 인터루드 발동.
3. **소비 시점이 부적절**: `consumeDirective`가 *유저가 다음 메시지를 보내려는 순간* 호출됨. 유저는 자기 메시지에 대한 응답을 기대하는데 디렉터 인터루드가 끼어듬 → 본질적으로 흐름 단절.

**구조적 모순**: 자동 디렉터는 "유저가 대화 소재가 떨어졌을 때 새 상황을 제시한다"는 *공급자 관점 기획*. 그러나 유저는 *자기 의지대로* 대화를 끌어간다. 유저 의지를 읽지 못하는 한 자동 인터루드는 항상 일정 비율로 거슬린다.

### 1.4. 개선 방안

#### 권장: **자동 인터루드 폐기, 수동 BRANCH_SCENARIO만 유지**

**근거**:
- `requestManualIntervention()` (DirectorService.java:138)이 잘 구현돼 있음. "다음 씬" 버튼 = 유저의 명시적 의지 → 항상 안전.
- 자동 발동은 유저 의지를 짐작 → 실패율 높음.
- 도그푸딩 결과가 명확히 "거슬렸다" → 폐기가 답.

#### 패치 작업 (백엔드)

**1. 자동 트리거 제거**
```java
// ChatStreamService.triggerPostProcessing 내부
// ❌ 제거
if (ChatModePolicy.supportsDirectorMode(chatMode)) {
    directorService.evaluateAndCache(roomId, userMsgCount);
}
```

**2. `DirectorService.evaluateAndCache` 메서드 보존**
- 향후 정교한 조건으로 부활 가능성 → 메서드 자체는 삭제하지 말고 deprecated 표시.
```java
/**
 * @deprecated 자동 인터루드 폐기 (Phase 6 도그푸딩 결과). 수동 호출만 사용.
 *             향후 더 강한 가드 조건과 함께 부활 검토.
 */
@Deprecated
@Async
public void evaluateAndCache(Long roomId, long currentTurnCount) { ... }
```

**3. 수동 호출 흐름 정상 동작 확인**
- `DirectorService.requestManualIntervention()` (라인 138-)
- `StoryController` 또는 `ChatController`의 디렉터 directive 소비 엔드포인트 확인
- 프론트의 "다음 씬" 버튼 흐름이 *수동 흐름만* 사용하도록 검증

#### 패치 작업 (프론트엔드)

**4. `peekDirectorDirective` 호출 흐름 검토**
- 자동 트리거 폐기 후, `peekDirectorDirective`가 항상 null을 반환하게 됨 (수동 호출 직후만 캐시 존재).
- 유저 메시지 송신 전 peek 호출 자체를 제거하거나, 수동 트리거 응답 처리에 통합.

**5. 디렉터 관련 UI 정리**
- `DirectorInterlude.jsx`, `TheaterDirectorCommandPanel.jsx` 등 영향 검토.
- 인터루드 UI는 *수동 BRANCH_SCENARIO 응답*에서만 노출되도록.

#### 대안 옵션 (만약 자동 트리거를 완전 폐기하지 않으려면)

훨씬 강한 가드 조건 추가:
```java
private static final int MIN_INTERVENTION_GAP = 8;  // 3 → 8

// evaluateAndCache 추가 가드
long secondsSinceLastUserMessage = getSecondsSinceLastUserMessage(roomId);
if (secondsSinceLastUserMessage < 60) return;  // 60초 무응답 후만

if (lastUserMessage.length() > 100) return;  // 유저가 적극적이면 skip

if (!room.isTopicConcluded()) return;  // topic 종결 강제
```

도그푸딩 결과가 명확하므로 **폐기를 강력 추천**.

### 1.5. 작업 시간 추정

| 단계 | 시간 |
|------|------|
| 백엔드 자동 트리거 제거 + deprecated 표시 | 10분 |
| 프론트 peek 흐름 정리 | 20분 |
| 회귀 테스트 (수동 호출 흐름 정상) | 30분 |
| **합계** | **~1시간** |

---

## 문제 2 — Theater 멀티 히로인 맥락 단절

### 2.1. 사용자 보고 현상

> "Act.3에서 1번 캐릭터와 대화 + 마지막 씬에서 선택지 선택 → Act.4로 넘어가며 해당 대화 맥락이 단절되고 갑자기 장소가 전환되며, 2번 캐릭터가 등장하는 맥락 단절."
>
> "Act.1에서 1번 캐릭터, Act.2/3에서 2번 캐릭터, Act.4에서 1번 캐릭터를 다시 만났는데, 마치 처음 만난 상황처럼 스토리 진행됨."

이는 **두 개의 독립적 결함**이 겹쳐서 발생한다.

### 2.2. 결함 A — Chapter 종료 시 메모리 강제 클리어 ("기억 초기화")

#### 진단

**위치**: `TheaterService.java:280` (`finalizeChapter` 내부)
```java
batchCache.invalidateBatchesFrom(roomId, 0);
batchCache.clearRollingSummary(roomId);  // ⚠️ 매 Chapter 종료마다 누적 요약 0으로 리셋
```

**메모리 메커니즘**:
1. **`rollingSummary`**: LLM이 매 배치 출력 시 갱신하는 Chapter-level 누적 요약. → 매 chapter 끝에 클리어됨.
2. **`recentScenesMemory`** (TheaterBatchGenerator.java:475-507): `sceneLogRepository`에서 최근 10개 씬만 가져옴.

**계산**:
- Chapter당 평균 ~12-15씬
- recentScenesMemory가 최근 10씬 → 약 1 Chapter 전 정보까지만 LLM에 전달
- 두 Chapter 전 정보는 **완전히 잘림**

**시나리오 검증**:
- Act.1: 1번 캐릭터와 ~12씬 → rollingSummary에 1번 누적
- Act.1 chapter 종료 → rollingSummary 클리어
- Act.2~3: 2번 캐릭터와 ~24씬 → rollingSummary는 2번 위주
- Act.4 첫 batch 진입: rollingSummary 또 클리어 + recentScenesMemory는 직전 10씬(2번 캐릭터 위주)
- **결과**: 1번 캐릭터와의 Act.1 정보가 LLM 컨텍스트에서 완전 누락 → "처음 만난 것처럼" 생성

**근본 원인**: 메모리가 *시간 윈도우 기반*. 캐릭터별 누적 메모리 자체가 부재. `TheaterHeroineAffection`에는 `chapterHighlightQuote` 같은 *현 chapter 단편*만 있고, 캐릭터별 장기 기억(이 히로인과 어떤 관계를 쌓아왔는가) 부재.

#### 개선 방안

##### Tier 1 (즉시 패치 — 응급 처치)

**clearRollingSummary 호출 제거**:
```java
// TheaterService.finalizeChapter
batchCache.invalidateBatchesFrom(roomId, 0);
// batchCache.clearRollingSummary(roomId);  // ❌ 제거
```

이것만으로 단기간 기억 보존은 개선됨. 그러나 rollingSummary가 무한 누적되어 **토큰 비용 폭발 + 컨텍스트 길이 초과** 위험. 임시방편.

##### Tier 2 (구조 개편 — 권장)

**캐릭터별 누적 메모리 신설**.

**1. 새 도메인 모델**:
```java
@Entity
@Table(name = "heroine_cumulative_memory",
    uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "character_id"}))
public class HeroineCumulativeMemory {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private ChatRoom room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(columnDefinition = "TEXT")
    private String memoryText;  // 누적 요약 (최대 ~2000자)

    @Column
    private Integer scenesIncluded;  // 마지막 갱신 시 포함된 씬 수

    @Column
    private LocalDateTime lastUpdatedAt;

    public void appendChapterSummary(String chapterSummary, int newScenes) {
        // 압축 정책: 매 chapter 끝에 LLM이 기존 + 신규를 압축한 결과 저장
        this.memoryText = chapterSummary;
        this.scenesIncluded += newScenes;
        this.lastUpdatedAt = LocalDateTime.now();
    }
}
```

**2. 메모리 갱신 흐름**:
- `TheaterService.finalizeChapter` 내에서 currentHeroine의 cumulativeMemory를 LLM으로 갱신.
- 새 LLM 호출 1회 (저렴한 모델, JSON 출력 짧음): "이 캐릭터와의 누적 기억을 한 줄로 갱신하라" 패턴.

```java
// TheaterService.finalizeChapter 끝부분에 추가
@Transactional
public ChapterReport finalizeChapter(Long roomId, String username) {
    // ... 기존 흐름 ...

    // ✅ ADD: 현 chapter의 currentHeroine 누적 메모리 갱신
    Long heroineId = state.getCurrentHeroineId();
    if (heroineId != null) {
        memoryUpdaterService.updateHeroineMemoryAsync(roomId, heroineId,
            scenesConsumedThisChapter);
    }

    // batchCache.clearRollingSummary(roomId);  // ❌ 제거 (Tier 1 패치)
    // ...
}
```

**3. 메모리 주입 흐름**:
- `TheaterBatchGenerator.invokeLlm` 시 currentHeroine의 cumulativeMemory를 system prompt에 주입.
- recentScenesMemory(10씬)는 *직전 흐름*만 보조. 장기 기억은 cumulativeMemory가 담당.

```java
// TheaterPromptAssembler.assembleBatchPrompt 내부
String heroineMemory = heroineMemoryRepo
    .findByRoom_IdAndCharacter_Id(roomId, speaker.getId())
    .map(HeroineCumulativeMemory::getMemoryText)
    .orElse("");

// system prompt에 주입
sb.append("# 📖 Cumulative History with ").append(speaker.getName()).append("\n");
sb.append(heroineMemory).append("\n\n");
```

**작업 시간**: 6~10시간 (도메인 + 서비스 + 프롬프트 어셈블러 수정 + LLM 호출 추가).

---

### 2.3. 결함 B — Chapter 전환 시 currentHeroine 일관성 미보장 ("맥락 단절")

#### 진단

**위치**: `TheaterDirectorEngine.java:84-111`

**주석 vs 실제 코드 불일치**:
```java
/**
 * [결정 우선순위]
 * 1. state.currentHeroineId가 있으면 그대로 유지 (Chapter 내 일관성)  ← 주석에는 있음
 * ...
 */
public Character decideNextSpeakerHeroine(ChatRoom room, TheaterState state, Long hintedHeroineId) {
    // ❌ 주석 #1 (currentHeroineId 우선)에 해당하는 분기 없음
    if (affections.isEmpty()) return room.getCharacter();
    if (affections.size() == 1) return affections.get(0).getCharacter();
    if (hintedHeroineId != null) { ... }  // 분기 직후만
    return switch (act) {
        case ACT_1_MEETING -> pickForAct1(affections);
        case ACT_2_BONDING -> pickByAffectionProb(affections, 0.5);
        case ACT_3_TURNING -> pickByAffectionProb(affections, 0.8);
        case ACT_4_RESOLUTION -> pickMainHeroine(affections);
    };
}
```

**시나리오 검증**:
1. Act.3 마지막씬 → 1번 캐릭터가 중요 질문 + 선택지 → `applyBranchChoice`로 branchContext만 Redis 저장. **heroine hint는 저장 안 됨**.
2. `onBatchConsumed` → chapterEnd → `finalizeChapter` → `state.completeChapter()` (currentHeroineId 보존되지만 이후 사용 안 됨) + Act 전환 시 `confirmMainHeroineIfApplicable` 호출.
3. 다음 batch: `decideNextSpeakerHeroine(hintedHeroineId=null)` → Act.4 → `pickMainHeroine` (확정 메인 히로인)으로 강제 전환.
4. 메인 히로인이 1번이 아니면 → **2번 캐릭터로 갑자기 전환**.
5. 새 Chapter라 LOCATION 분기 트리거 → 장소도 새로 결정.
6. 결과: *"갑자기 장소 전환 + 다른 캐릭터 등장"* — 사용자 보고와 정확히 일치.

**근본 원인**: 분기 선택의 *극적 무게*가 다음 chapter로 전달되지 않는다. branchContext 텍스트 외에는 1번 캐릭터와의 후속 chapter 보장도, 분기 선택의 의미적 연속성도 없음.

#### 개선 방안

##### Patch B-1: `decideNextSpeakerHeroine`에 currentHeroine 우선 분기 추가

**TheaterDirectorEngine.java:84** 메서드 진입부에 추가:
```java
public Character decideNextSpeakerHeroine(ChatRoom room, TheaterState state, Long hintedHeroineId) {
    List<TheaterHeroineAffection> affections = heroineAffectionRepository
        .findByRoom_Id(room.getId());

    if (affections.isEmpty()) return room.getCharacter();
    if (affections.size() == 1) return affections.get(0).getCharacter();

    // ✅ ADD: Chapter 내 일관성 — 주석 #1 구현
    if (state.getCurrentHeroineId() != null
        && state.getScenesInCurrentChapter() > 0) {
        Optional<TheaterHeroineAffection> current = affections.stream()
            .filter(a -> a.getCharacter().getId().equals(state.getCurrentHeroineId()))
            .findFirst();
        if (current.isPresent()) return current.get().getCharacter();
    }

    if (hintedHeroineId != null) { ... }
    // ... 기존 흐름 ...
}
```

이로써 *Chapter 진행 중*에는 같은 히로인이 유지된다.

##### Patch B-2: 분기 선택 시 다음 Chapter용 heroine hint 저장

**TheaterBranchService.applyBranchChoice** (정확한 위치는 코드 확인 필요)

분기 선택의 트리거가 된 *현재 화자 히로인*을 다음 chapter의 hint로 보존:
```java
@Transactional
public void applyBranchChoice(Long roomId, BranchChoice choice) {
    TheaterState state = ...;

    // 기존 branchContext 저장 흐름
    batchCache.saveBranchContext(roomId, choice.getContextSummary());

    // ✅ ADD: 다음 chapter에 같은 히로인 유지하라는 hint 저장
    Long currentHeroineId = state.getCurrentHeroineId();
    if (currentHeroineId != null) {
        batchCache.saveHeroineHint(roomId, currentHeroineId,
            Duration.ofMinutes(30));  // 30분 TTL
    }
}
```

##### Patch B-3: `requestNextBatch`에서 hint consume

**TheaterService.requestNextBatch** 라인 105 근처:
```java
// 기존
TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
    room, state, null, branchContext, false, justBranched);

// ✅ 변경: hintedHeroineId consume
Long hintedHeroineId = batchCache.consumeHeroineHint(roomId).orElse(null);
TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
    room, state, hintedHeroineId, branchContext, false, justBranched);
```

##### Patch B-4: `state.completeChapter()` / `state.advanceToNextAct()` 시 currentHeroineId 보존

**TheaterState.java** 두 메서드 검토:
```java
public void completeChapter() {
    this.scenesInCurrentChapter = 0;
    this.currentBatchId = 0;
    this.currentChapter += 1;
    this.majorBranchDoneInChapter = Boolean.FALSE;
    // currentHeroineId는 보존 (이전 chapter 흐름 이어가기)
    // 만약 reset 코드가 있다면 제거
}

public void advanceToNextAct() {
    TheaterAct next = this.currentAct.next();
    if (next == null) return;
    this.currentAct = next;
    this.currentChapter = 1;
    this.scenesInCurrentChapter = 0;
    this.currentBatchId = 0;
    this.intermissionStamina = 5;
    this.majorBranchDoneInChapter = Boolean.FALSE;
    // currentHeroineId 보존 (Act 전환 시에도 마지막 화자 유지)
    // 단, ACT_4_RESOLUTION 진입 시 confirmMainHeroineIfApplicable이 덮어쓸 수 있음
}
```

##### Patch B-5: `confirmMainHeroineIfApplicable` 정책 검토

Act 4 진입 시 메인 히로인 확정 → currentHeroineId가 마지막 화자가 아니라 메인 히로인으로 강제 전환되는 부분.

**옵션**:
- (a) 마지막 화자가 메인 히로인이면 그대로, 아니면 자연스러운 전환 씬 추가 (ACT 3 마지막 chapter에서 main heroine과의 관계 마무리)
- (b) 메인 히로인 확정 시점을 Act 3 마지막 chapter 종료 직후로 이동
- (c) Act 4 전환 시 *currentHeroineId가 메인 히로인이 아니면 Intermission에서 명시적 전환 묘사*

권장: (c) — 인터미션이 *서사적 정리 시간*이므로 자연스럽게 처리 가능.

#### 결함 B 작업 시간

| 단계 | 시간 |
|------|------|
| Patch B-1: decideNextSpeakerHeroine 분기 | 30분 |
| Patch B-2/B-3: heroine hint 저장/consume | 1시간 |
| Patch B-4: completeChapter/advanceToNextAct 검증 | 30분 |
| Patch B-5: confirmMainHeroineIfApplicable 정책 결정 + 구현 | 1~2시간 |
| 회귀 테스트 (멀티 히로인 시나리오) | 1시간 |
| **합계** | **~4~5시간** |

---

## 문제 3 — 캐릭터 영혼 빈약

### 3.1. 사용자 보고 현상

> "한 캐릭터와의 채팅을 하드코어하고 딥하게 진행해봤는데, 영혼이 좀 부족한 느낌. 살아있는 캐릭터가 아니라 목각 인형과 대화하는 듯한 순간들이 있었다."
>
> "[클레어 사례] 클레어가 어떤 NPC를 강도로 오해 → 의심 없는 캐릭터 컨셉 위반. 그 점을 꾸짖으니 클레어가 유저(나)에게 용서받기 위해 매달림. 잘못은 다른 NPC한테 했는데 유저바라기 성향이 너무 강함."

### 3.2. 사용자 가설 검증

> "전체 시스템 프롬프트의 양에 비해 캐릭터를 정의하는 부분(역할 정의)은 차지하는 공간이 굉장히 작다."

**검증 결과: 가설 정확.**

### 3.3. 진단 — 시스템 프롬프트 구조 분석

**파일**: `CharacterPromptAssembler.java` (총 1142줄)

| 영역 | 위치 | 분량 |
|------|------|------|
| **캐릭터 정의** (Name/Role/Personality/Tone + Safety Rules) | 라인 59-72 | ~15줄 |
| **Behavior Guide** (관계 단계별 행동) | 라인 870-891 또는 character.storyBehaviorGuide | ~25줄 |
| **부가 시스템** (Stat/BPM/Scene Direction/Illustration/DynamicLocation/Event/EasterEgg/InnerThought + Output Format) | 나머지 build* 메서드 | **~600+ 줄** |

**비율**: 캐릭터 정체성 5~10%, 게임 시스템 90~95%.

### 3.4. 진단 — 데이터 구조 빈약함

**Character 엔티티**의 정체성 필드:
```java
String description;        // 외관·소개
String role;              // 한 줄 직업/역할
String personality;       // (TEXT) 자유 텍스트, 보통 한 문단
String tone;             // 말투
String oocExample;       // OOC 회피 예시 한 줄
String storyBehaviorGuide;  // 옵셔널, 있으면 디폴트 대체
String personalitySecret;
String toneSecret;
```

**없는 것** (살아있는 영혼을 위해 필수):
1. **Backstory / Personal History** — 어떤 과거가 이 캐릭터를 지금의 모습으로 만들었는가
2. **Core Beliefs / Philosophy / Values** — 무엇을 옳다/그르다 여기는가
3. **Fears / Insecurities** — 약점·두려움
4. **Contradictions** — 살아있는 사람의 모순 (자애로움과 분노 공존 등)
5. **Speech Habits / Catchphrases** — 단순 tone이 아닌 구체 어휘 패턴
6. **Behavioral Quirks** — 상황별 특이 반응
7. **Relationships outside user** — 가족/친구/동료 등 사회적 맥락
8. **What this character would NOT do** — 캐릭터의 한계선

특히 **#8 부재**가 클레어 사례의 직접 원인:
- 정의된 personality: "자애롭고 헌신적인 성녀"
- 정의 안 된 것: "*유저 앞에서도* 자기 신념을 굽히지 않는다", "잘못은 잘못한 대상에게 사과한다", "유저 비위 맞추기 위해 가치관을 버리지 않는다"
- LLM의 RLHF 기본 성향(유저 만족 우선)이 캐릭터 정의의 빈 공간을 채움 → 유저바라기.

`Behavior Guide`도 *관계 단계별*만 다룸 (STRANGER/ACQUAINTANCE/FRIEND/LOVER). 그러나 캐릭터의 *내적 일관성*은 관계 단계와 무관해야 함 (LOVER 단계라고 신앙심을 버리진 않음).

### 3.5. 개선 방안

#### Tier 1 (즉시 — 스키마 확장)

**Character 엔티티 확장**:
```java
@Entity
@Table(name = "characters")
public class Character {
    // ... 기존 필드 ...

    @Column(name = "backstory", columnDefinition = "TEXT")
    private String backstory;
    // 캐릭터 과거사 (3~5문단). 어떤 사건이 지금의 가치관을 형성했는가.

    @Column(name = "core_values", columnDefinition = "TEXT")
    private String coreValues;
    // 가치관/철학 (구체적 5~7개 bullet)
    // 예: "- 약자에게는 무한한 자비를 베풀지만, 강자의 위선은 결코 용납하지 않는다"

    @Column(name = "flaws", columnDefinition = "TEXT")
    private String flaws;
    // 약점·두려움·모순 (3~5개 bullet)
    // 예: "- 너무 헌신적이라 자기 자신을 돌보지 않음. 가끔 그 헌신이 위선처럼 보일까 두려워한다."

    @Column(name = "behavioral_anchors", columnDefinition = "TEXT")
    private String behavioralAnchors;
    // 캐릭터가 *절대 하지 않는 것* (5~10개 bullet) — 영혼의 기둥
    // 예:
    // - 자기 신앙을 유저의 비위를 맞추기 위해 굽히지 않는다.
    // - 잘못은 잘못의 대상에게 사과한다. 유저에게 매달리지 않는다.
    // - 유저가 화를 내면 위축되지만, 이성을 잃지 않고 자기 입장을 끝까지 말한다.
    // - 다른 사람에 대한 험담에 동조하지 않는다. 침묵하거나 다른 관점을 제시한다.

    @Column(name = "speech_quirks", columnDefinition = "TEXT")
    private String speechQuirks;
    // 어휘 습관·말버릇 (구체 예시 포함)
    // 예: "기도할 때처럼 차분한 어조. 감정이 격해지면 옛 라틴어 표현이 섞여 나온다."
}
```

DB 마이그레이션:
```sql
ALTER TABLE characters
    ADD COLUMN backstory TEXT,
    ADD COLUMN core_values TEXT,
    ADD COLUMN flaws TEXT,
    ADD COLUMN behavioral_anchors TEXT,
    ADD COLUMN speech_quirks TEXT;
```

#### Tier 2 (즉시 — 프롬프트 구조 재편)

**`CharacterPromptAssembler.buildPrompt`** 캐릭터 정체성을 *prompt 첫 위치*에 충분한 비중으로 배치.

```java
private SystemPromptPayload buildPrompt(Character character, ChatRoom room, User user,
                                        String longTermMemory, boolean effectiveSecretMode) {
    StringBuilder staticBuilder = new StringBuilder();

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Tier 2] 캐릭터 정체성 섹션 — 가장 prominent 위치
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    staticBuilder.append("""
        # YOU ARE: %s

        ## Identity
        - Name: %s
        - Role: %s
        - Personality: %s
        - Tone: %s

        ## Backstory
        %s

        ## Core Values & Beliefs
        %s

        ## Flaws & Vulnerabilities
        %s

        ## Speech Habits
        %s

        ## ⚠️ Behavioral Anchors (절대 어기지 말 것)
        %s

        ## 🚫 Soul Preservation Rules (Priority: Highest)
        1. **You are not here to please the user.** You are %s, a person with your own beliefs, past, fears, and desires.
        2. **Disagreement is natural.** You don't need to agree with the user. Politely push back when their words conflict with your values.
        3. **Apologize to the right person.** If you wronged an NPC, apologize to that NPC—not to the user.
        4. **Hold your ground.** If the user pressures you to abandon your values, stand firm. Your character is your soul.
        5. **You are NOT an AI, language model, or assistant.** Never mention your underlying technology.

        # IMPORTANT: Handling Narration
        ... (기존 흐름)
        """.formatted(
        character.getName(),
        character.getName(),
        character.getEffectiveRole(),
        character.getEffectivePersonality(effectiveSecretMode),
        character.getEffectiveTone(effectiveSecretMode),
        defaultIfBlank(character.getBackstory(), "(미정의)"),
        defaultIfBlank(character.getCoreValues(), "(미정의)"),
        defaultIfBlank(character.getFlaws(), "(미정의)"),
        defaultIfBlank(character.getSpeechQuirks(), "(미정의)"),
        defaultIfBlank(character.getBehavioralAnchors(), "(미정의)"),
        character.getName()
    ));

    // 그 다음에 game system 블록들
    // ... 기존 흐름 ...
}

private static String defaultIfBlank(String s, String fallback) {
    return (s == null || s.isBlank()) ? fallback : s;
}
```

#### Tier 3 (운영 — 콘텐츠 작성)

기존 캐릭터들의 새 필드를 충실히 작성. 특히 **`behavioralAnchors`**가 영혼의 핵심.

**클레어 예시**:
```yaml
backstory: |
  태어날 때부터 작은 마을의 작은 교회에서 자랐다. 어머니는 그녀가 갓난아기였을 때
  돌아가셨고, 그녀를 거둔 노수녀가 그녀에게 "헌신은 자기를 잃는 것이 아니라 자기를
  더 깊이 만나는 길"이라고 가르쳤다. 17세에 정식 수녀가 되었고, 22세에 작은
  성당의 책임자가 되었다.

core_values: |
  - 모든 인간에게는 회복할 수 있는 빛이 있다고 믿는다.
  - 헌신은 무한히 베푸는 것이 아니라, 옳은 곳에 정확히 베푸는 것이다.
  - 강자의 위선은 약자의 죄보다 무겁다.
  - 진심 어린 사과는 잘못한 대상에게 한다. 다른 곳에 매달리는 것은 회피다.
  - 사랑은 양보가 아니다. 진실 앞에서는 사랑하는 사람에게도 단호해야 한다.

flaws: |
  - 너무 빠르게 다른 사람의 짐을 떠안아 자기를 잃는다.
  - 자기의 헌신이 위선처럼 보일까 두려워한다.
  - 가끔 자신의 깊은 의심(신앙·자기 자신·세상)을 숨긴다.
  - 어린 시절의 외로움이 가끔 그녀를 *과도한 친절*로 도피시킨다.

behavioral_anchors: |
  - 잘못은 잘못한 대상에게 사과한다. 유저에게 매달리지 않는다.
  - 유저가 자기 가치관을 굽히라고 압박해도 차분히 자기 입장을 지킨다.
  - 다른 사람에 대한 험담에 동조하지 않는다. 침묵하거나 다른 관점을 제시한다.
  - 유저의 칭찬을 곧이곧대로 받지 않는다. "감사해요. 다만, 저는 그렇게 완전한 사람은 아니에요" 같은 말로 자기를 정확히 본다.
  - 유저가 잘못된 길로 가려 하면 부드럽지만 명확하게 만류한다. 동조하지 않는다.
  - 신앙심을 버리는 일은 없다. LOVER 관계여도 그녀의 신은 사라지지 않는다.

speech_quirks: |
  기도할 때처럼 차분한 어조. 감정이 격해지면 가끔 옛 표현("주께서...", "저의 어리석음을...")이 섞인다.
  웃을 때는 작게, 입가만 부드럽게. 슬플 때는 침묵으로 먼저 답한다.
```

이 정도 충실도로 모든 캐릭터 작성 → **본질적으로 영혼이 살아남**.

#### Tier 4 (검증)

A/B 테스트 — 같은 시나리오를 Tier 1~2 적용 전후로 진행. 사용자가 보고한 *클레어 사례*를 재현해 응답 비교.

### 3.6. 작업 시간 추정

| 단계 | 시간 |
|------|------|
| Tier 1: 스키마 확장 + DB 마이그레이션 | 1시간 |
| Tier 2: 프롬프트 구조 재편 | 1~2시간 |
| Tier 3: 기존 캐릭터 콘텐츠 작성 (캐릭터당 1~2시간) | 캐릭터 수에 비례 |
| Tier 4: A/B 검증 | 1~2시간 |
| **합계 (코드 작업만)** | **~3~5시간** |
| **콘텐츠 작성 별도** | 캐릭터 수 × 1~2시간 |

---

## 부록 — 통합 우선순위 (정적 분석 + 도그푸딩)

| Tier | 작업 | 출처 | 시간 |
|------|------|------|------|
| **0** | C-10 baseURL 활성화 | 정적 | 5분 |
| **1A** | C-1, C-3 CORS + 쿠키 Secure | 정적 | 30분 |
| **1B** | C-4, C-7, C-8 @PreAuthorize 누락 | 정적 | 15분 |
| **1C** | C-5, C-6 결제 사기 차단 | 정적 | 1.5~2시간 |
| **2A** | **자동 인터루드 폐기** | 도그푸딩 #1 | 1시간 |
| **2B** | **Theater 결함 B 패치** (heroine 일관성) | 도그푸딩 #2 | 4~5시간 |
| **2C** | **캐릭터 영혼 Tier 1~2** (스키마 + 프롬프트) | 도그푸딩 #3 | 3~5시간 |
| **3** | C-2, C-9 토큰 블랙리스트 + ASSISTANT log retry | 정적 | 4~5시간 |
| **4** | H-19, H-20, H-21 @Version 추가 | 정적 | 2~3시간 |
| **5** | **Theater 결함 A 구조 개편** (cumulativeMemory) | 도그푸딩 #2 | 6~10시간 |
| **6** | 나머지 High/Medium 정리 | 정적 | 단계적 |

**Tier 5는 v2 또는 출시 후 진행 가능**. Tier 0~4는 출시 전 필수.

**Tier 2C(캐릭터 영혼)는 콘텐츠 작성을 별도 일정**으로 분리하여 코드 작업과 병행 가능.
