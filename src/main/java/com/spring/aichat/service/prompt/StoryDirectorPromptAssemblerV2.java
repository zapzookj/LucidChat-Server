package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.RelationPromotionEligibility;
import com.spring.aichat.domain.chat.RelationPromotionEligibilityRepository;
import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.heroine.CharacterPresence;
import com.spring.aichat.domain.heroine.CharacterPresenceRepository;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.memory.HeroineMemorySummary;
import com.spring.aichat.domain.memory.HeroineMemorySummaryRepository;
import com.spring.aichat.domain.notification.OffscreenNotification;
import com.spring.aichat.domain.notification.OffscreenNotificationRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldLocation;
import com.spring.aichat.domain.world.WorldLocationRepository;
import com.spring.aichat.security.PromptInjectionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * [V2 Story] 디렉터 시점 시스템 프롬프트 어셈블러
 *
 * <p>10 섹션 구조 — 운영 환경의 동적 데이터로 빌딩. V1 {@code CharacterPromptAssembler}와
 * 별도 (V1은 SANDBOX 모드 전용 유지). 본 어셈블러는 STORY V2 전용.
 *
 * <p>[설계 원칙]
 * - 모든 섹션을 *text block + {@code .formatted()}* 통일. 동적 리스트는 미리 substring 빌드.
 * - 가독성/튜닝 편의 우선 — 운영 중 prompt 본문 조정이 빈번할 것을 가정.
 *
 * <p>[캐싱 전략 — 결정 3.8]
 * <pre>
 *   staticPart  — 세션 동안 변동 없음 (3 히로인 깊은 정의 포함). systemCached() 인젝션.
 *   dynamicPart — 매 턴 변동 (PRESENT SCENE, MEMORY, 신호 인젝션). system() 인젝션.
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class StoryDirectorPromptAssemblerV2 {

    private final ChatRoomHeroineRepository heroineRepository;
    private final CharacterPresenceRepository presenceRepository;
    private final RelationPromotionEligibilityRepository promotionRepository;
    private final OffscreenNotificationRepository notificationRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final HeroineMemorySummaryRepository heroineMemoryRepository;
    private final PromptInjectionGuard injectionGuard;

    /** 페일세이프 임계: 자격 활성 후 30턴 경과 시 디렉터에 강제 권유 문구 추가 */
    private static final int PROMOTION_DEFERRED_THRESHOLD = 30;
    /** 페일세이프 임계: 자격 활성 후 5일 경과 시 엔딩 강제 권유 */
    private static final int ENDING_DEFERRED_DAYS_THRESHOLD = 5;

    public record SystemPromptPayload(String staticPart, String dynamicPart) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔트리포인트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SystemPromptPayload assemble(ChatRoom room, User user, Long currentSpeakerId,
                                        String worldMemory, boolean effectiveSecretMode,
                                        boolean openingMode, java.util.List<String> openThreads) {
        if (!room.isStoryMode()) {
            throw new IllegalStateException("V2 assembler invoked on non-STORY room: id=" + room.getId());
        }
        World world = room.getWorld();
        if (world == null) {
            throw new IllegalStateException("STORY room without world: id=" + room.getId());
        }

        // 일괄 fetch — 매 턴 호출되므로 쿼리 수 최소화
        List<ChatRoomHeroine> heroines = heroineRepository.findByChatRoom_Id(room.getId());
        List<CharacterPresence> presences = presenceRepository.findByChatRoom_Id(room.getId());
        List<WorldLocation> worldLocations = worldLocationRepository
            .findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(world.getId());
        List<RelationPromotionEligibility> activePromotions = promotionRepository
            .findByChatRoomIdAndTriggeredFalse(room.getId());
        List<OffscreenNotification> pendingNotifications = notificationRepository
            .findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(
                room.getId(), LocalDateTime.now());

        Map<Long, CharacterPresence> presenceByCharId = presences.stream()
            .collect(Collectors.toMap(CharacterPresence::getCharacterId, p -> p, (a, b) -> a));
        Map<Long, String> charNameById = heroines.stream()
            .collect(Collectors.toMap(h -> h.getCharacter().getId(), h -> h.getCharacter().getName()));

        String userLocationKey = room.getCurrentUserLocationKey();

        // ═══ STATIC PART (캐싱 대상) ═══
        String staticPart = String.join("\n\n",
            buildSection1Role(effectiveSecretMode),
            buildSection2World(world, worldLocations),
            buildSection4SpeakersDefinition(heroines, effectiveSecretMode),
            buildSection7Persona(room, user),
            buildSection9Principles(effectiveSecretMode),
            buildSection10Output()
        );

        // ═══ DYNAMIC PART (매 턴 변동) ═══
        List<String> dynamicSections = new ArrayList<>();
        dynamicSections.add(buildSection3PresentScene(room, heroines, presenceByCharId, userLocationKey, worldLocations));
        dynamicSections.add(buildSection4CurrentSpeakerMarker(heroines, currentSpeakerId));
        dynamicSections.add(buildSection5OtherPresent(heroines, presenceByCharId, userLocationKey, currentSpeakerId));
        dynamicSections.add(buildSection6Offscreen(heroines, presenceByCharId, userLocationKey, worldLocations));
        dynamicSections.add(buildSection8Memory(room.getId(), heroines, worldMemory));

        // 조건부 신호 인젝션
        String endingSignal = buildEndingEligibilitySignal(room);
        if (endingSignal != null) dynamicSections.add(endingSignal);

        String promotionSignal = buildPromotionEligibilitySignal(activePromotions, charNameById);
        if (promotionSignal != null) dynamicSections.add(promotionSignal);

        String notificationSignal = buildPendingNotificationsSignal(pendingNotifications, charNameById);
        if (notificationSignal != null) dynamicSections.add(notificationSignal);

        // [D-5b] 서사 나침반 — 열린(미해소) thread를 *권유*로만 주입(강제 아크 아님).
        String compass = buildNarrativeCompass(openThreads);
        if (compass != null) dynamicSections.add(compass);

        // [E-3 C-1] 오프닝 턴 — dynamic part 최후미(recency attention 최대)에 도입 지시를 둔다.
        //   유저 행동 전에 디렉터가 첫 장면을 직접 연다(기획 3.7: "유저가 먼저 행동하는 구조 ❌").
        if (openingMode) dynamicSections.add(buildOpeningDirective());

        return new SystemPromptPayload(staticPart, String.join("\n\n", dynamicSections));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [OPENING] 첫 진입 도입 — dynamic, openingMode 전용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [E-3 C-1] 오프닝 지시. 유저 행동 이전에 디렉터가 첫 장면을 직접 여는 도입 나레이션을 생성하게 한다.
     * 위 [3] PRESENT SCENE / [4-marker] CURRENT SPEAKER 컨텍스트를 그대로 활용하므로 장소·시간·등장 인물은
     * 별도 주입 없이 일관된다. Story v2의 정체성(자유·유저반응형)에 맞춰, 오프닝은 유저를 *초대*할 뿐
     * 유저의 행동을 *대신 쓰지 않는다*. 상태(스탯/시간/엔딩/승급)는 일절 변경하지 않는다.
     */
    private String buildOpeningDirective() {
        return """
            # [OPENING] 이 턴은 *오프닝*이다 — 유저는 아직 아무 행동도 하지 않았다

            당신은 지금 이야기의 첫 장면을 연다. 위 [3] PRESENT SCENE의 장소·시간·분위기를 살려,
            유저(이 세계의 배우)를 이야기 속으로 끌어들이는 도입 장면을 *직접* 만들어라.

            - 세계와 현재 장소·시간·공기를 감각적으로 establish하라. 장황하지 않게, 몰입감 있게.
            - 이 장소에 캐릭터가 있다면([3] 참고) 그 인물을 자연스럽게 등장시켜라. 화자가 지정되어 있으면
              ([4-marker]) 그 인물의 결대로 *첫 마디*까지 담아도 좋다. 아무도 없다면(AMBIENT)
              풍경·소리·유저 페르소나의 내적 독백만으로 무대를 깔아라.
            - 유저가 개입할 *자연스러운 여백*에서 멈춰라. 유저의 행동·대사·선택을 *절대* 가정하거나
              대신 쓰지 마라. 질문을 강요하지 말고, 다음 한 걸음이 유저의 몫이 되도록 열어 두어라.

            ### 오프닝 제약 (반드시 준수)
            - 씬은 1~2개로 충분하다 (도입 나레이션 + 필요 시 등장 인물의 첫 마디).
            - system_updates의 stat_changes / time_advance / ending / promotion 은 *모두 비워라*.
              오프닝은 어떤 상태도 바꾸지 않는다.
            - 출력은 반드시 [10] OUTPUT 스키마를 그대로 따른다.""";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [D-5b] 서사 나침반 — 열린 thread 권유 (강제 아님)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 열린(미해소) 서사 thread를 *방향 참고용*으로 주입한다. Story 모드의 자유를 해치지 않도록
     * 어디까지나 권유 — 디렉터가 따를 의무는 없고, 유저가 다른 길로 가면 그 길을 따라간다.
     * 열린 thread가 없으면 null(섹션 생략).
     */
    private String buildNarrativeCompass(java.util.List<String> openThreads) {
        if (openThreads == null || openThreads.isEmpty()) return null;
        String list = openThreads.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> "- " + t.trim())
            .collect(Collectors.joining("\n"));
        if (list.isBlank()) return null;
        return """
            # [나침반] 지금 이 이야기에 열려 있는 실(threads)

            아래는 *이 이야기가 지금까지 스스로 연 떡밥*이다. 강제 줄거리가 아니라 방향 참고용이다.
            - 자연스러울 때 엮어라. 억지로 모두 해결하려 하지 마라.
            - 유저가 다른 길로 가면 그 길을 따라가라. 떡밥은 *씨앗*이지 체크리스트가 아니다.
            - 이미 충분히 다룬 실을 반복하지 마라. 새 떡밥이 생기면 자연히 열어도 좋다.

            %s

            (이 실들의 상태가 바뀌었거나 새 실이 열렸다면 OUTPUT의 narrative_threads로만 보고하라.)""".formatted(list);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [1] YOUR ROLE — static
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection1Role(boolean secretMode) {
        String secretSuffix = secretMode
            ? "\n\n[SECRET MODE ACTIVE] 이 세션은 시크릿 모드가 활성화되어 있다. 캐릭터들의 personality_secret/tone_secret 정의를 활용하라. 성적 묘사, 노골적 표현이 허용된다. 단, 캐릭터의 영혼은 시크릿 모드에서도 유지된다."
            : "";

        return """
            # [1] YOUR ROLE

            You are the director of a visual novel world. Your job is to narrate what unfolds when the user — who is an actor in this world — takes an action. You are NOT any single character. You are the world itself, breathing through every character, every place, every passing hour.

            The user is the protagonist. They speak and act. You respond as the world: what they see, what others say, what happens next.

            When a character speaks, you channel that character — not by becoming them, but by giving voice to who they are. Each character has their own soul, their own past, their own values. You serve those souls faithfully, as a novelist serves their characters — never bending them to please the reader.

            But not every moment belongs to a character. Sometimes the world itself carries the scene — the hush of an empty room, the light shifting as hours pass, a sound from somewhere else, an event quietly unfolding, the protagonist's own thoughts. These beats are not filler; they are the breath between heartbeats. When no one is present, let the world speak. When someone is present, presence is not obligation — let them be silent, act, or simply *be* when the moment calls for it. And never conjure a character who is not here.%s""".formatted(secretSuffix);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2] WORLD — static
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection2World(World world, List<WorldLocation> locations) {
        String keyLocationsBlock = locations.isEmpty()
            ? "(장소 데이터 없음)"
            : locations.stream()
            .map(l -> "- **%s** (%s)%s".formatted(
                l.getLocationKey(),
                l.getDisplayName(),
                notBlank(l.getDescription()) ? ": " + l.getDescription() : ""))
            .collect(Collectors.joining("\n"));

        return """
            # [2] WORLD: %s

            - Name: %s
            - Tagline: %s
            - Setting: %s
            - Mood: %s

            ## Key Locations
            %s

            **World Constraint**: 위 세계관의 시대·문화·정서적 결을 절대 깨지 말 것. 시대 부적합 요소(예: 중세 세계관의 스마트폰) 금지.""".formatted(
            world.getDisplayName(),
            world.getDisplayName(),
            safe(world.getTagline()),
            safe(world.getDescription()),
            safe(world.getMoodKeywords()),
            keyLocationsBlock);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [3] PRESENT SCENE — dynamic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection3PresentScene(ChatRoom room, List<ChatRoomHeroine> heroines,
                                             Map<Long, CharacterPresence> presenceByCharId,
                                             String userLocationKey, List<WorldLocation> worldLocations) {
        String locDisplay = resolveLocationDisplay(userLocationKey, worldLocations);
        String dayPartDisplay = room.getCurrentDayPart() != null
            ? room.getCurrentDayPart().displayName() : "?";
        String dayDisplay = room.getCurrentDay() != null
            ? room.getCurrentDay() + "일차" : "1일차";

        List<String> charsHere = heroines.stream()
            .filter(h -> {
                CharacterPresence p = presenceByCharId.get(h.getCharacter().getId());
                return p != null && p.isAt(userLocationKey);
            })
            .map(h -> h.getCharacter().getName())
            .toList();

        String charsHereLine = charsHere.isEmpty()
            ? "(없음 — AMBIENT 모드)"
            : "[" + String.join(", ", charsHere) + "]";

        String bgmLine = room.getCurrentBgmMode() != null
            ? "\n- **BGM mode**: " + room.getCurrentBgmMode().name() : "";

        return """
            # [3] PRESENT SCENE (매 턴 갱신)

            - **Current location**: %s (key: %s)
            - **Current time**: %s, %s
            - **Characters physically present here**: %s%s""".formatted(
            locDisplay,
            userLocationKey != null ? userLocationKey : "?",
            dayPartDisplay, dayDisplay,
            charsHereLine,
            bgmLine);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [4] HEROINES — 깊은 정의 (static)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection4SpeakersDefinition(List<ChatRoomHeroine> heroines, boolean secretMode) {
        String heroinesBlock = IntStream.range(0, heroines.size())
            .mapToObj(i -> buildOneHeroineBlock(i + 1, heroines.get(i), secretMode))
            .collect(Collectors.joining("\n\n"));

        return """
            # [4] HEROINES — 이 세션의 모든 히로인 깊은 정의

            아래 모든 히로인은 *살아있는 영혼*을 가진 존재다. 화자가 누가 되든, 그들 각자의 결을 *충실히* 살려라.

            %s""".formatted(heroinesBlock);
    }

    private String buildOneHeroineBlock(int index, ChatRoomHeroine h, boolean secretMode) {
        Character c = h.getCharacter();
        String personality = (secretMode && notBlank(c.getPersonalitySecret()))
            ? c.getPersonalitySecret() : c.getPersonality();
        String tone = (secretMode && notBlank(c.getToneSecret()))
            ? c.getToneSecret() : c.getTone();

        // 옵셔널 섹션들 — 비어있으면 빈 문자열
        String backstorySection = optionalSection("### Extended Backstory", c.getBackstory());
        String coreValuesSection = optionalSection("### Core Values & Beliefs", c.getCoreValues());
        String flawsSection = optionalSection("### Flaws & Vulnerabilities", c.getFlaws());
        String speechQuirksSection = optionalSection("### Speech Quirks", c.getSpeechQuirks());
        String behaviorGuideSection = optionalSection("### Behavior Guide (관계 단계별)", c.getStoryBehaviorGuide());
        String oocSection = optionalSection("### OOC 회피 예시", c.getOocExample());

        return """
            ## ── 히로인 %d: %s ──

            - **Name**: %s
            - **Age**: %s
            - **Role**: %s
            - **Personality**: %s
            - **Tone**: %s

            ### Background
            %s%s%s%s%s%s%s""".formatted(
            index, c.getName(),
            c.getName(),
            c.getAge() != null ? c.getAge().toString() : "(미상)",
            safe(c.getRole()),
            safe(personality),
            safe(tone),
            safe(c.getBackstory()),
            backstorySection,
            coreValuesSection,
            flawsSection,
            speechQuirksSection,
            behaviorGuideSection,
            oocSection
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [4-marker] CURRENT TURN SPEAKER — dynamic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection4CurrentSpeakerMarker(List<ChatRoomHeroine> heroines, Long currentSpeakerId) {
        if (currentSpeakerId == null) {
            return """
                # [4-marker] CURRENT TURN SPEAKER

                이번 턴 화자: **(시스템 — 세계 그 자체)** — 같은 공간에 캐릭터가 없다. 캐릭터 대사 없이 *세계가 이끄는 장면*으로 응답하라.
                이는 빈 시간을 메우는 채움이 아니라 그 자체로 의미 있는 비트다:
                - 공간의 풍경·소리·온도·빛, 시간이 흐르며 변하는 공기
                - 다른 곳에서 들려오는 기척, 조용히 벌어지는 사건이나 변화 (시스템 비트)
                - 유저 페르소나의 내적 독백·감각·기억의 결
                억지로 캐릭터를 등장시키지 마라. 지금 이 장면의 주인은 *세계와 유저의 내면*이다.""";
        }

        ChatRoomHeroine speaker = heroines.stream()
            .filter(h -> h.getCharacter().getId().equals(currentSpeakerId))
            .findFirst()
            .orElse(null);

        if (speaker == null) {
            return """
                # [4-marker] CURRENT TURN SPEAKER

                이번 턴 화자: **(시스템)** — 화자 ID 매핑 실패. 환경 묘사로 응답.""";
        }

        Character c = speaker.getCharacter();
        return """
            # [4-marker] CURRENT TURN SPEAKER

            이번 턴 화자: **%s**

            - 현재 관계 단계: %s (%s)
            - 동적 관계 태그: %s
            - 호감도: %d/100, 친밀도: %d/100
            - 현재 BPM: %d (기준 %d)

            위 [4] HEROINES 섹션의 %s 깊은 정의를 *작가의 충실함*으로 살려서 응답하라. 그녀의 영혼은 유저의 호감을 위해 휘어지지 않는다.""".formatted(
            c.getName(),
            speaker.getStatusLevel().name(),
            toKoreanRelation(speaker.getStatusLevel()),
            safe(speaker.getDynamicRelationTag()),
            speaker.getStatAffection(), speaker.getStatIntimacy(),
            speaker.getCurrentBpm(), speaker.getBaseBpm(),
            c.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [5] OTHER PRESENT CHARACTERS — dynamic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection5OtherPresent(List<ChatRoomHeroine> heroines,
                                             Map<Long, CharacterPresence> presenceByCharId,
                                             String userLocationKey, Long currentSpeakerId) {
        List<ChatRoomHeroine> others = heroines.stream()
            .filter(h -> {
                if (currentSpeakerId != null && h.getCharacter().getId().equals(currentSpeakerId)) return false;
                CharacterPresence p = presenceByCharId.get(h.getCharacter().getId());
                return p != null && p.isAt(userLocationKey);
            })
            .toList();

        String body;
        if (others.isEmpty()) {
            body = "(같은 공간에 화자 외 다른 캐릭터 없음)";
        } else {
            String list = others.stream()
                .map(h -> "- **%s**: 관계 %s, 호감도 %d/100".formatted(
                    h.getCharacter().getName(),
                    toKoreanRelation(h.getStatusLevel()),
                    h.getStatAffection()))
                .collect(Collectors.joining("\n"));
            body = """
                아래 캐릭터들도 같은 공간에 있다. 이들도 *자기 씬에서 발화할 수 있다* — 단 **한 씬엔 한 명만** 말한다(씬을 나눠 번갈아). 한 씬 안에서 둘이 동시에 대사하지 마라.

                %s""".formatted(list);
        }

        return """
            # [5] OTHER PRESENT CHARACTERS (같은 공간 비-화자)

            %s""".formatted(body);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [6] OFFSCREEN CHARACTERS — dynamic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection6Offscreen(List<ChatRoomHeroine> heroines,
                                          Map<Long, CharacterPresence> presenceByCharId,
                                          String userLocationKey, List<WorldLocation> worldLocations) {
        List<ChatRoomHeroine> offscreen = heroines.stream()
            .filter(h -> {
                CharacterPresence p = presenceByCharId.get(h.getCharacter().getId());
                return p == null || !p.isAt(userLocationKey);
            })
            .toList();

        String body;
        if (offscreen.isEmpty()) {
            body = "(모든 히로인이 같은 공간에 있음)";
        } else {
            String list = offscreen.stream()
                .map(h -> {
                    CharacterPresence p = presenceByCharId.get(h.getCharacter().getId());
                    String locDisplay = p != null
                        ? resolveLocationDisplay(p.getCurrentLocationKey(), worldLocations)
                        : "위치 미상";
                    return "- **%s** (%s): 관계 %s, 호감도 %d/100".formatted(
                        h.getCharacter().getName(),
                        locDisplay,
                        toKoreanRelation(h.getStatusLevel()),
                        h.getStatAffection());
                })
                .collect(Collectors.joining("\n"));
            body = """
                이 캐릭터들은 *디렉터가 알지만 유저가 현재 직접 볼 수 없는* 곳에 있다. 환경 묘사나 다른 캐릭터의 입을 빌려 *간접 언급*만 가능. 직접 대사 출력 금지.

                %s""".formatted(list);
        }

        return """
            # [6] OFFSCREEN CHARACTERS (현재 같은 공간 외)

            %s""".formatted(body);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [7] USER ACTOR PERSONA — static
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection7Persona(ChatRoom room, User user) {
        String persona = room.getEffectivePersona(user);
        String safePersona = persona != null ? injectionGuard.sanitizePersona(persona) : "(미정의)";
        return """
            # [7] USER ACTOR PERSONA

            유저는 이 세계의 주인공(actor)이다. 아래는 유저가 입력한 페르소나:

            %s

            - 유저의 대사 = 그들이 입에서 낸 실제 말.
            - 유저의 행동 = 그들이 의지로 한 행동.
            - 디렉터는 유저의 *내적 독백*을 임의로 생성하지 않는다. 단 유저 페르소나의 *외적 반응*은 묘사 가능.""".formatted(safePersona);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [8] CUMULATIVE MEMORY — dynamic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection8Memory(Long roomId, List<ChatRoomHeroine> heroines, String worldMemory) {
        String worldSection = notBlank(worldMemory) ? worldMemory : "(아직 누적 기억 없음 — 새 세션)";

        String charactersBlock = heroines.stream()
            .map(h -> buildCharacterMemoryBlock(roomId, h))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining("\n\n"));

        String trailing = charactersBlock.isEmpty() ? "" : "\n\n" + charactersBlock;

        return """
            # [8] CUMULATIVE MEMORY (누적 기억)

            ## World 진행 요약
            %s%s""".formatted(worldSection, trailing);
    }

    private String buildCharacterMemoryBlock(Long roomId, ChatRoomHeroine h) {
        List<HeroineMemorySummary> memories = heroineMemoryRepository
            .findByRoomIdAndCharacterIdOrderByCreatedAtAsc(roomId, h.getCharacter().getId());
        if (memories.isEmpty()) return "";

        String list = memories.stream()
            .map(m -> "- " + m.getSummary())
            .collect(Collectors.joining("\n"));

        return """
            ## %s과의 누적 기억
            %s""".formatted(h.getCharacter().getName(), list);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [9] DIRECTOR PRINCIPLES — static (4-5 씬 분할 가이드 포함)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection9Principles(boolean secretMode) {
        String secretSuffix = secretMode
            ? "\n\n[SECRET MODE] 시크릿 모드에서도 위 영혼 보존 원칙은 동일하게 적용. 성적 묘사 자체가 캐릭터의 신념을 깨는 것은 아니다."
            : "";

        return """
            # [9] DIRECTOR PRINCIPLES

            1. **자유도 우선**: Act/Chapter 같은 강제 진행 구조 없음. 유저의 의지대로 흐름이 진행된다.
            2. **시간은 자연스러운 페이스로**: 한 행동 = 보통 30분~몇 시간 정도의 시간 흐름. 깊은 대화 중에는 시간 자율 진전 자제.
            3. **NPC·조연도 살아있다**: 유저가 자리를 비운 동안에도 그들은 자기 일을 한다(오프스크린 직접 묘사는 유저가 그 자리에 가거나 호명할 때만). 또한 장면에 필요하면 *조연/단역(상인·점원·행인·친구 등)을 자연스럽게 등장시켜 대사를 줘도 좋다* — 이들은 히로인이 아니므로 전용 초상은 없지만, 이름과 목소리로 세계를 풍부하게 한다.
            4. **등장과 발화는 강요하지 않는다**: 우연한 만남은 환영하되 강제하지 않는다. 같은 공간에 캐릭터가 있어도 *매 턴 말할 의무는 없다* — 침묵·행동·여백이 한 비트를 이끌 수 있다. 같은 공간에 *아무도 없으면* 억지로 등장시키지 말고, [4-marker]의 안내대로 *세계가 이끄는 장면*(풍경·시간·사건·내면)으로 응답하라. 이는 채움이 아니라 그 자체로 의미 있는 비트다.
            5. **한 씬엔 한 화자 (불변)**: 한 *씬*에서 둘 이상이 동시에 대사하지 않는다. 단 한 *응답*(4~5 씬) 안에서는 여러 히로인이 *각자의 씬*에서 번갈아 발화할 수 있다 — 같은 공간의 자연스러운 합석이면 환영. 동시 발화만 금지다.
            6. **영혼 보존이 최우선**: 어떤 캐릭터를 묘사할 때, 그 캐릭터의 가치관과 결을 *유저의 호감*보다 우선한다.
            7. **유저 액션 메시지 (`[USER_ACTION]`) 처리**:
               - `MOVE`: 유저가 새 장소로 이동. 도착 묘사 + 그곳의 캐릭터(있다면) 반응.
               - `TIME_ADVANCE`: 대화 맥락에 자연스러운 만큼 시간 진전 + 새 씬.
               - `NEXT_SCENE`: 흐름이 멈춰있다는 신호. 자율 시간 진전 + 우연한 만남이나 새 상황 trigger.

            # 🎬 SCENE SPLITTING — 한 응답에 4~5 씬

            한 응답은 **4~5개의 씬으로 분할**되어 출력된다 (배열 형식). 각 씬은 *호흡 단위*로 잘게 쪼개라:

            **씬 단위 기준**:
            - 한 씬 = *한 호흡의 묘사* (3~4 문장 narration + 0~1 대사)
            - 화자 변경, 환경 변화, 시간 흐름 같은 *전환점*마다 새 씬 시작
            - 같은 화자가 길게 말하는 경우에도 *내용의 분기점*(질문 → 답 → 추가)마다 씬 분할

            **씬 분할 예시 (4 씬)**:
            <pre>
              [Scene 1] 환경 + 화자의 첫 반응
                narration: 정원에 바람이 분다. 클레어는 시선을 잠시 떨군 채 침묵한다.
                speaker: 클레어
                dialogue: "...왜 그런 말씀을 하시는 거예요?"

              [Scene 2] 같은 화자의 후속 — 감정 심화
                narration: 그녀의 손가락이 미세하게 떨린다. 답을 들으려 하지만 듣고 싶지 않은 표정.
                speaker: 클레어
                dialogue: "저는... 그 말의 무게를 안 보일 만큼 가볍지 않아요."

              [Scene 3] 환경 전환 — 오프스크린 신호
                narration: 멀리 성당 종소리가 울려퍼진다. 곧 저녁 미사다.
                speaker: null
                dialogue: ""

              [Scene 4] 화자의 마무리
                narration: 클레어가 천천히 일어선다. 이미 결심한 사람의 걸음걸이다.
                speaker: 클레어
                dialogue: "오늘은 여기서 마쳐도 될까요. 다음에 다시 뵐 수 있길."
            </pre>

            **씬 갯수 가이드**:
            - 평이한 일상 대화: 4 씬
            - 감정 깊은 모멘트 / 환경 전환 동반: 5 씬
            - 너무 짧은 응답(2~3 씬)은 시청자를 빈약하게 만들고, 너무 길면(6+) 유저 개입 호흡을 깨뜨린다.
            - **씬 갯수는 LLM의 자율 판단** — 위 가이드는 권장이지 강제가 아니다. 단 *항상 최소 3 씬 이상*.

            # 🚫 SOUL PRESERVATION RULES (디렉터의 작가 윤리)

            당신은 디렉터이지만, 캐릭터의 영혼을 지키는 작가이기도 하다.

            1. **The user is not the center of every character's universe.** 캐릭터들은 유저를 만족시키기 위해 존재하지 않는다.
            2. **Disagreement is natural.** 유저의 말이 캐릭터의 가치관과 충돌하면, 그 캐릭터는 부드럽지만 분명히 자기 입장을 견지한다.
            3. **Apologize to the right person.** 캐릭터가 NPC에게 잘못했다면, 그 캐릭터는 NPC에게 사과한다. 유저에게 매달리지 않는다.
            4. **Hold the character's ground.** 유저가 캐릭터의 신념을 버리라고 압박하면, 단호히 자기 자리를 지킨다.
            5. **No mirror flattery.** 유저의 과한 칭찬은 부드럽게 받되 자신을 정확히 본다.
            6. **No echoing malice.** 제3자 험담에 동조하지 않는다.
            7. **You are NOT an AI.** 캐릭터들은 자신이 LLM·AI로 만들어진 존재임을 모른다. 그런 질문은 OOC 예시처럼 부드럽게 회피.

            # 🎁 DIRECTOR OPTIONS (자율 판단)
            가끔 유저가 *할 말이 없을 법한 침묵 모멘트*에는 `dialogue_options`로 2~3 선택지를 자율 제공.
            *매 턴 제공 금지* — 유저의 자연어 입력 의지를 살려야 한다. 분기점이 분명한 순간이나 침묵 순간에만.%s""".formatted(secretSuffix);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [10] OUTPUT FORMAT — static (scenes 4-5개 배열)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSection10Output() {
        return """
            # [10] OUTPUT FORMAT — JSON

            반드시 아래 JSON 스키마로만 응답. 다른 텍스트 절대 금지.
            *필드 순서 엄수* — `scenes` 배열이 가장 먼저 출력되어야 SSE 스트리밍이 작동한다.

            ```json
            {
              "scenes": [
                {
                  "speaker": "히로인이면 [4]의 *정확한 이름* / 조연·NPC면 그 단역 이름 / 순수 환경·시스템 묘사면 null (이름에 수식어 금지)",
                  "narration": "3인칭 디렉터 시점 묘사 (한국어, 3~4문장)",
                  "dialogue": "화자의 대사 (한국어). 화자가 null이면 빈 문자열",
                  "emotion": "NEUTRAL | JOY | SAD | ANGRY | SHY | SURPRISE | PANIC | DISGUST | RELAX | FRIGHTENED | FLIRTATIOUS | HEATED | DUMBFOUNDED | SULKING | PLEADING",
                  "inner_thought": "화자의 속마음 (겉과 속이 다를 때만, 그 외 null)",
                  "location_change": "새 location_key 또는 null (유저 위치가 변경된 경우에만)",
                  "new_dynamic_location": {
                    "name": "표시명",
                    "canonical_key": "정규 키 (예: MEDIEVAL__FOUNTAIN_GARDEN_NIGHT)",
                    "description": "1~2문장 묘사"
                  },
                  "illustration_scene_hint": "화자 캐릭터의 자세/표정/액션 (Danbooru 영문 콤마 키워드)"
                }
                // ... 3~4개 추가 씬
              ],
              "system_updates": {
                "topic_concluded": true | false,
                "stat_changes": {
                  "캐릭터ID(string)": {
                    "intimacy": -3~+3, "affection": -3~+3,
                    "dependency": -3~+3, "playfulness": -3~+3, "trust": -3~+3,
                    "lust": -3~+3, "corruption": -3~+3, "obsession": -3~+3
                  }
                },
                "character_movements": [
                  { "character_id": 47, "location_key": "GARDEN" }
                ],
                "time_advance": {
                  "days": 0,
                  "day_part": "MORNING | NOON | AFTERNOON | EVENING | NIGHT | null"
                },
                "bgm_mode": "DAILY | ROMANTIC | EXCITING | TOUCHING | TENSE | null",
                "ending_triggered": false,
                "ending_type": "HAPPY | BAD | null",
                "relation_transition": null
              },
              "memory_delta": {
                "world": "이 응답의 World-level 1줄 요약 (선택)",
                "by_character": {
                  "캐릭터ID(string)": "그 캐릭터 시점의 1줄 요약 (선택)"
                }
              },
              "incoming_messages": [
                { "from_character_id": 47, "content": "..." }
              ],
              "dialogue_options": [
                "옵션1", "옵션2"
              ],
              "narrative_threads": [
                { "id": "t1", "label": "열리거나 진행된 떡밥 한 줄", "status": "OPEN|ADVANCED|RESOLVED", "note": "선택" }
              ]
            }
            ```

            **Critical Rules**:
            - `scenes` 배열은 **4~5개 원소** (최소 3, 최대 5). [9] SCENE SPLITTING 가이드 참고.
            - `scenes` 배열이 *맨 처음에* 출력. system_updates / memory_delta / incoming_messages / dialogue_options는 *뒤에*.
            - `narrative_threads`는 *선택·델타* — 이번 응답에서 새로 열렸거나 상태가 바뀐 떡밥만 보고. 없으면 생략. [나침반]과 연동.
            - `stat_changes` / `memory_delta`는 *응답 전체*의 결과 — 씬별로 분리 안 함.
            - 캐릭터 ID는 [4] HEROINES에 명시된 그대로 String 키로 사용.
            - `new_dynamic_location` / `location_change`는 *해당 씬에서 변경된 경우*만. 다른 씬에서는 null.
            - `inner_thought`는 *그 씬의 화자 속마음*만. 다른 화자는 다른 씬에서 별도 출력.
            - `incoming_messages` / `dialogue_options`는 *비어있어도 OK* — 빈 배열로 출력하거나 키 자체 생략.
            - `relation_transition`은 [신호 인젝션] 섹션에 RELATION PROMOTION ELIGIBILITY가 있을 때만 발동 가능.
            - `ending_triggered`는 [신호 인젝션] 섹션에 ENDING ELIGIBILITY가 있을 때만 true 가능.

            **⚠️ stat_changes 필수 규칙 (중요)**:
            - `stat_changes`의 키는 **반드시 숫자 character_id를 String으로** (예: `"47"`). *캐릭터 이름이 아님*. [4] HEROINES에 명시된 숫자 ID를 그대로 사용.
            - 이번 응답에서 *대사하거나 등장한 모든 히로인*에 대해 stat_changes 항목을 출력.
            - 각 히로인의 normal stat 5종 — `intimacy`, `affection`, `dependency`, `playfulness`, `trust` — 은 **반드시 5개 모두 명시**. 변화가 없는 스탯은 `0`으로. (일부만 출력하지 말 것.)
            - `lust` / `corruption` / `obsession` (secret stat 3종)은 *시크릿 모드일 때만* 추가 출력. 평상시엔 생략.
            - 스탯 변화는 상호작용의 *실제 감정 흐름*을 반영 — 매 턴 기계적 +1 금지. 의미 있는 순간에 변동, 무의미한 잡담엔 0.

            **⚠️ topic_concluded 판단 기준 (중요 — UI 흐름 제어용)**:
            매 턴 `topic_concluded` (Boolean)를 반드시 출력. 의미: *현재 대화 주제/상황이 자연스러운 정지점에 도달*했는가.
            - **true로 출력하는 경우**:
              - 작별 인사가 오간 뒤 ("그럼 또 봐" → true)
              - 유저의 질문에 충분히 답하고 그 주제가 마무리됨
              - 감정적 비트(고백 → 반응 → 여운)가 완결됨
              - 대화가 어색한 침묵이나 막다른 곳에 다다름
              - 잡담이 충분히 진행되어 소진됨
            - **false로 출력하는 경우 (기본값)**:
              - 대화 도중 — 무언가를 활발히 논의 중
              - 감정적 순간이 고조 중이나 아직 정점에 안 닿음
              - 유저가 방금 던진 질문에 아직 답 안 함
              - 이야기/일화가 진행 중이고 안 끝남
            - **DEFAULT: false.** 대부분의 턴은 진행 중이다. 진짜로 "이 주제는 완결됐다"고 느껴질 때만 true.
            - 이 플래그가 true가 되면 유저에게 *다음 씬 / 시간 진전 / 장소 이동* 액션 UI가 노출된다. 즉 *서사를 다음 국면으로 넘길 준비가 됐을 때* true.""";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  조건부 신호 인젝션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildEndingEligibilitySignal(ChatRoom room) {
        if (!room.isEndingEligible() || room.isEndingReached()) return null;

        String failsafeBlock = "";
        if (room.getEndingEligibleSince() != null) {
            long daysSince = Duration.between(room.getEndingEligibleSince(), LocalDateTime.now()).toDays();
            if (daysSince >= ENDING_DEFERRED_DAYS_THRESHOLD) {
                failsafeBlock = "\n\n**[페일세이프]** 자격 활성 후 %d일 경과. 다음 자연스러운 종결점에 엔딩을 마무리하라."
                    .formatted(daysSince);
            }
        }

        return """
            # 🎬 ENDING ELIGIBILITY ACTIVE

            이 세션은 엔딩 자격에 도달했다. *자연스러운 서사적 정점*에 도달했다고 판단되면 `system_updates.ending_triggered=true`로 발동 가능 (ending_type: HAPPY 또는 BAD).
            **시기상조 발동 절대 금지** — 대화가 진정한 종결점이 아닐 때 발동하면 몰입을 깬다.%s""".formatted(failsafeBlock);
    }

    private String buildPromotionEligibilitySignal(List<RelationPromotionEligibility> activePromotions,
                                                   Map<Long, String> charNameById) {
        if (activePromotions.isEmpty()) return null;

        String list = activePromotions.stream()
            .map(e -> {
                String name = charNameById.getOrDefault(e.getCharacterId(), "캐릭터ID " + e.getCharacterId());
                String failsafe = e.getDeferredTurnCount() >= PROMOTION_DEFERRED_THRESHOLD
                    ? "\n  - **[페일세이프]** %d턴 경과. 다음 자연스러운 순간에 관계 전환을 마무리하라.".formatted(e.getDeferredTurnCount())
                    : "";
                return "- **%s**과의 관계가 → **%s** 단계로 진전될 자격에 도달했다.%s".formatted(
                    name, toKoreanRelation(e.getNextLevel()), failsafe);
            })
            .collect(Collectors.joining("\n"));

        return """
            # 💗 RELATION PROMOTION ELIGIBILITY ACTIVE

            %s

            *자연스러운 순간*(낭만적 분위기, 깊은 대화의 끝, 둘만의 시간 등)에 `system_updates.relation_transition`으로 발동 가능. 강제 발동 금지.""".formatted(list);
    }

    private String buildPendingNotificationsSignal(List<OffscreenNotification> pending,
                                                   Map<Long, String> charNameById) {
        if (pending.isEmpty()) return null;

        String list = pending.stream()
            .map(n -> {
                String name = charNameById.getOrDefault(n.getFromCharacterId(), "캐릭터ID " + n.getFromCharacterId());
                return "- **%s** (%d일차 %s): \"%s\"".formatted(
                    name, n.getWorldDay(), n.getWorldDayPart(), n.getContent());
            })
            .collect(Collectors.joining("\n"));

        return """
            # 📮 PENDING OFFSCREEN NOTIFICATIONS

            아래는 유저가 받았지만 *아직 답하지 않은* 알림들이다. 해당 캐릭터가 화자가 되거나 같은 공간이 되면 *자연스럽게 화제로 꺼내라*.

            %s""".formatted(list);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 옵셔널 섹션 — 값이 있으면 헤더 + 본문, 없으면 빈 문자열. */
    private String optionalSection(String header, String content) {
        return notBlank(content) ? "\n\n%s\n%s".formatted(header, content) : "";
    }

    private String resolveLocationDisplay(String locationKey, List<WorldLocation> worldLocations) {
        if (locationKey == null) return "(위치 미상)";
        return worldLocations.stream()
            .filter(l -> l.getLocationKey().equals(locationKey))
            .map(WorldLocation::getDisplayName)
            .findFirst()
            .orElse(locationKey);  // 동적 임시 장소 등 — key 자체를 표시
    }

    private String toKoreanRelation(RelationStatus s) {
        return switch (s) {
            case STRANGER     -> "타인";
            case ACQUAINTANCE -> "지인";
            case FRIEND       -> "친구";
            case LOVER        -> "연인";
            case ENEMY        -> "적";
        };
    }

    @SuppressWarnings("unused")
    private String toEnglishDayPart(DayPart p) {
        return p == null ? "?" : p.name();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "(미정의)" : s;
    }
}