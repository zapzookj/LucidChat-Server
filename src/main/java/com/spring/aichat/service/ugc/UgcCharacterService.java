package com.spring.aichat.service.ugc;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterSource;
import com.spring.aichat.domain.enums.CharacterVisibility;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.ugc.WorldReviewStatus;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [UGC v1] 바인딩 완료된 UGC 캐릭터의 소유자 조작 — 공개/Secret 신청, 텍스트 수정, 목록.
 *
 * <p>접근 규칙: 소유자 검증 실패는 404 은닉(타인 캐릭터 존재 비노출).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcCharacterService {

    static final int EXPLORE_MAX_LIMIT = 30;

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final UgcWorldRepository ugcWorldRepository; // [세계관 빌더] 연결 검증·이름 해석
    private final UgcVlmPrefilterService vlmPrefilterService; // [P0 PoC-5] 공개 신청 이미지 자문 스캔
    private final UgcRoutineGenerationService routineGenerationService; // [P2 STORY 개방 1단] 루틴 자동생성
    private final UgcModerationService moderationService; // [E-5.2.a] 텍스트 수정 경로 하드 키워드 게이트

    // ── 공개 심사 경로 ──

    @Transactional
    public void requestPublish(String username, Long characterId, boolean cancel) {
        Character character = ownedUgc(username, characterId);
        if (cancel) {
            character.cancelPublishRequest();
        } else {
            character.requestPublish();
            // [2026-07-30 P0 PoC-5] VLM 이미지 프리필터 — 어드민 자문 스캔 (비동기·비차단·플래그 기본 off)
            vlmPrefilterService.screenForPublishAsync(character.getId(), character.getOwnerUserId(),
                character.getName(), character.getThumbnailUrl(), character.getDefaultImageUrl());
        }
        log.info("[UGC] 공개 신청 {}: characterId={}, username={}", cancel ? "취소" : "접수", characterId, username);
    }

    /**
     * [2026-07-30 P0] 소유자 자진 공개 철회 — PUBLIC(또는 PENDING_PUBLIC) → PRIVATE 즉시 회귀.
     * PENDING_PUBLIC 취소는 기존 requestPublish(cancel=true)와 결과 동일(중복 허용 — 프론트 단순화).
     */
    @Transactional
    public void unpublish(String username, Long characterId) {
        Character character = ownedUgc(username, characterId);
        // [리뷰픽스] 멱등 — 이미 PRIVATE(중복 클릭·경합)이면 500 대신 no-op
        if (character.getVisibility() == CharacterVisibility.PRIVATE) {
            log.info("[UGC] 공개 철회(소유자) — 이미 비공개, no-op: characterId={}", characterId);
            return;
        }
        character.unpublish(null);
        log.info("[UGC] 공개 철회(소유자): characterId={}, username={}", characterId, username);
    }

    // ── Secret 단독 심사 경로 (2026-07-17 결정 — PRIVATE 유지 캐릭터도 신청 가능) ──

    @Transactional
    public void requestSecretReview(String username, Long characterId) {
        Character character = ownedUgc(username, characterId);

        // [안건 9-C · docs/19_assets/decisions_confirmed.md §C] 나이 미달 신청 차단.
        // ⚠ 이건 게이트가 아니라 '안내'다 — 서버측 최종 판정은 A(SecretModeService.isCharacterSecretEligible)와
        // B(AdminUgcReviewService.review의 secretApprove)에 있다. 여기만 막으면 어드민 API 직타로
        // secretEligible=true가 되므로(§F ②, beta-activate 사고와 같은 형태) 이 검사에 의존하지 말 것.
        // age == null은 통과시킨다 — 기존 UGC 캐릭터가 전부 null이라 여기서 막으면 신청면이 통째로 죽는다.
        Integer age = character.getAge();
        if (age != null && age < UgcModerationService.MIN_CHARACTER_AGE) {
            throw new BadRequestException(
                "%d세 미만 캐릭터는 Secret 모드를 신청할 수 없어요.".formatted(UgcModerationService.MIN_CHARACTER_AGE));
        }

        character.requestSecretReview();
        log.info("[UGC] Secret 심사 신청: characterId={}, username={}", characterId, username);
    }

    // ── 완성 화면 인라인 텍스트 수정 (에셋 무관 — 무료) ──

    @Transactional
    public void updateTexts(String username, Long characterId, UgcDtos.UpdateTextsRequest req) {
        Character character = ownedUgc(username, characterId);

        // [D-19 / D-3.6 · decision_agenda D-19] 길이 상한 400 거부. 이 경로는 바인딩이 끝난
        // Character를 직접 수정하므로, 검증이 없으면 varchar 초과가 커밋 시점 500으로 터진다
        // (role은 이 DTO에 없다 — name/tagline/tone만 varchar, personality·firstGreeting은 TEXT).
        UgcTextLimits.requireMax(req.name(), UgcTextLimits.NAME_MAX, "이름");
        UgcTextLimits.requireMax(req.tagline(), UgcTextLimits.TAGLINE_MAX, "한 줄 소개");
        UgcTextLimits.requireMax(req.tone(), UgcTextLimits.TONE_MAX, "말투");

        // [E-5.2.a] 하드 키워드 게이트가 이 수정 경로에만 없었다 — 생성 4곳
        // (CharacterCreationService:133/135/273/391)과 페르소나 2곳(UserPersonaService:187/189)에는
        // 전부 있다. 게이트가 없으면 심사를 통과시킨 뒤 personality/firstGreeting을 갈아끼워
        // 생성 게이트를 통과한 적 없는 문장을 프롬프트에 넣을 수 있다.
        assertChangedTextsAllowed(character, req);

        boolean revertedToReview = character.updateUgcTexts(
            req.name(), req.tagline(), req.personality(), req.tone(), req.firstGreeting());
        // [안건 20 (A) · decisions_confirmed §B #20] 승인 후 심사 대상 필드 수정 → PENDING_PUBLIC 자동 회귀.
        // 회귀 판정 자체는 Character.updateUgcTexts 안(불변식)에 있고 여기선 로깅만 한다.
        if (revertedToReview) {
            log.info("[UGC] 승인 후 텍스트 수정 → 재심사 회귀(PENDING_PUBLIC): characterId={}, username={}",
                characterId, username);
        }
        // [2026-07-31 난이도] 무료 편집 — 무효값·null은 유지(NORMAL도 명시값)
        var difficulty = com.spring.aichat.domain.enums.CharacterDifficulty.fromStringOrNull(req.difficulty());
        if (difficulty != null) character.updateDifficulty(difficulty);
    }

    /**
     * [E-5.2.a] '변경된 값만' 하드 키워드 검사.
     *
     * <p>★ 5필드를 통째로 검사하면 안 된다. {@code StudioPage.jsx:586-603}이 editForm을 기존 값으로
     * 프리필해 5필드를 항상 함께 PATCH하는데, personality·tone·firstGreeting은 Stage 0 LLM 산출물이라
     * {@link UgcModerationService#assertRawConceptAllowed}를 통과한 적이 없다(생성 시 검사되는 것은
     * 유저 원문과 구조화 산출의 minorSignal·age뿐이다). 따라서 기존 문장에 '중학생 때부터 알던
     * 소꿉친구' 같은 표현이 하나만 있어도 그 캐릭터는 이름 한 글자조차 못 고치는 <b>영구 편집 불가</b>
     * 상태가 되고, 400 문구는 어느 필드가 문제인지도 알려주지 않는다 — 착취를 막으려다 정상 유저를
     * 세우는 전형이다(CLAUDE.md §D).
     *
     * <p>판정 규칙은 {@link Character#updateUgcTexts}와 동일하게 맞춘다(비-null · 비-blank ·
     * 현재값과 상이). 규칙이 갈리면 '검사는 통과했는데 저장은 안 되는'(또는 그 반대) 비대칭이 생긴다.
     * tagline의 blank=삭제는 검사 대상이 아니다 — 지우는 데 키워드 검사가 필요 없다.
     *
     * <p>실측(2026-09-03 프로드): 기존 UGC 15건의 personality/tone/first_greeting/tagline에
     * 하드 키워드 0건 — 지금 잠기는 캐릭터는 없다. 다만 LLM 산출 텍스트는 구조적으로 이 게이트
     * 밖이므로 델타 검사가 아니면 언제든 재발한다.
     */
    private void assertChangedTextsAllowed(Character character, UgcDtos.UpdateTextsRequest req) {
        assertIfChanged(req.name(), character.getName());
        assertIfChanged(req.tagline(), character.getTagline());
        assertIfChanged(req.personality(), character.getPersonality());
        assertIfChanged(req.tone(), character.getTone());
        assertIfChanged(req.firstGreeting(), character.getFirstGreeting());
    }

    /** 값이 실제로 바뀔 때만 원문 게이트를 태운다. 규칙은 Character.updateUgcTexts와 1:1. */
    private void assertIfChanged(String next, String current) {
        if (next == null || next.isBlank()) return;
        if (next.equals(current)) return;
        moderationService.assertRawConceptAllowed(next);
    }

    // ── [세계관 빌더] 세계관 연결/변경 (에셋 무관 — 무료, 카드 메뉴 소급 연결) ──

    /**
     * 세계관 연결/변경/해제. 심사 우회 방지 게이트: 월드 검수는 캐릭터 공개 심사에 피기백되므로,
     * <b>이미 PUBLIC인 캐릭터에는 APPROVED 월드만</b> 연결 허용(2026-07-20 종원 확정).
     * PRIVATE/PENDING_PUBLIC은 자유 연결(PENDING이면 심사 상세에 최신 월드가 자동 반영).
     * 공식 세계관은 검수 대상이 아니므로 상시 허용.
     */
    @Transactional
    public void linkWorld(String username, Long characterId, UgcDtos.WorldLinkRequest req) {
        Character character = ownedUgc(username, characterId);

        // [리뷰 픽스] 공개 심사 중 월드 교체 차단 — 관리자가 상세에서 본 월드와 판정 시점 월드가
        // 달라지는 TOCTOU(미심사 월드가 승인·공개) 방지. 심사 취소 후 변경 가능.
        if (character.getVisibility() == CharacterVisibility.PENDING_PUBLIC) {
            throw new BadRequestException("공개 심사 중에는 세계관을 변경할 수 없어요. 심사 취소 후 변경해 주세요.");
        }

        WorldId official = null;
        if (req != null && req.officialWorldId() != null && !req.officialWorldId().isBlank()) {
            official = WorldId.fromStringOrNull(req.officialWorldId());
            if (official == null) {
                throw new BadRequestException("알 수 없는 세계관입니다: " + req.officialWorldId());
            }
        }
        Long ugcWorldId = req != null ? req.ugcWorldId() : null;
        if (official != null && ugcWorldId != null) {
            throw new BadRequestException("세계관은 하나만 연결할 수 있어요.");
        }

        if (ugcWorldId != null) {
            UgcWorld world = ugcWorldRepository.findByIdAndOwnerUserId(ugcWorldId, character.getOwnerUserId())
                .orElseThrow(this::hiddenNotFound); // 타인/미존재 월드 은닉
            if (character.getVisibility() == CharacterVisibility.PUBLIC
                && world.getReviewStatus() != WorldReviewStatus.APPROVED) {
                throw new BadRequestException("공개된 캐릭터에는 검수 승인된 세계관만 연결할 수 있어요.");
            }
            character.linkUgcWorld(world.getId());
        } else if (official != null) {
            character.linkOfficialWorld(official);
        } else {
            character.unlinkWorld();
        }
        // [2026-07-30 P2 STORY 개방 1단 · 리뷰픽스] 연결 변경 → 루틴 재생성(해제면 삭제).
        // 커밋 전 @Async 발화는 비동기 스레드가 구(舊) 월드 연결을 읽는 레이스(스테일 루틴 영속) —
        // afterCommit으로 미뤄 새 연결이 확정된 뒤에만 재생성한다.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    routineGenerationService.regenerateForCharacterAsync(characterId);
                }
            });
        log.info("[UGC] 세계관 연결 변경: characterId={}, official={}, ugcWorldId={}", characterId, official, ugcWorldId);
    }

    /** [세계관 빌더] 카드 뷰용 UGC 월드 이름 일괄 해석 (N+1 방지). */
    @Transactional(readOnly = true)
    public Map<Long, String> ugcWorldNames(List<Character> characters) {
        List<Long> ids = characters.stream()
            .map(Character::getUgcWorldId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) return Map.of();
        return ugcWorldRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(UgcWorld::getId, UgcWorld::getName, (a, b) -> a));
    }

    // ── 목록 ──

    @Transactional(readOnly = true)
    public List<Character> myCharacters(String username) {
        User user = findUser(username);
        return characterRepository.findByOwnerUserIdOrderByIdDesc(user.getId());
    }

    /** 탐색 피드 — 공개 UGC 최신순 커서 페이지네이션 + 창작자 닉네임 조인. */
    @Transactional(readOnly = true)
    public UgcDtos.ExploreResponse explore(Long cursor, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), EXPLORE_MAX_LIMIT);
        PageRequest page = PageRequest.of(0, effectiveLimit + 1); // +1 = 다음 페이지 존재 판별

        List<Character> rows = (cursor == null)
            ? characterRepository.findBySourceAndVisibilityAndHiddenFalseOrderByIdDesc(
                CharacterSource.UGC, CharacterVisibility.PUBLIC, page)
            : characterRepository.findBySourceAndVisibilityAndHiddenFalseAndIdLessThanOrderByIdDesc(
                CharacterSource.UGC, CharacterVisibility.PUBLIC, cursor, page);

        boolean hasNext = rows.size() > effectiveLimit;
        List<Character> items = hasNext ? rows.subList(0, effectiveLimit) : rows;

        Map<Long, String> nicknames = userRepository.findAllById(
                items.stream().map(Character::getOwnerUserId).filter(java.util.Objects::nonNull).distinct().toList())
            .stream()
            .collect(Collectors.toMap(User::getId, u ->
                (u.getNickname() != null && !u.getNickname().isBlank()) ? u.getNickname() : "크리에이터",
                (a, b) -> a));

        List<UgcDtos.ExploreItem> views = items.stream()
            .map(c -> new UgcDtos.ExploreItem(
                c.getId(), c.getName(), c.getTagline(), c.getThumbnailUrl(),
                nicknames.getOrDefault(c.getOwnerUserId(), "크리에이터")))
            .toList();

        Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
        return new UgcDtos.ExploreResponse(views, nextCursor);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private Character ownedUgc(String username, Long characterId) {
        User user = findUser(username);
        Character character = characterRepository.findById(characterId)
            .orElseThrow(this::hiddenNotFound);
        if (!character.isUgc() || !character.isOwnedBy(user.getId())) {
            throw hiddenNotFound();
        }
        return character;
    }

    private NotFoundException hiddenNotFound() {
        return new NotFoundException("캐릭터를 찾을 수 없습니다.");
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }
}
