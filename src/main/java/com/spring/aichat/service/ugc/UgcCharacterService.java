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

    // ── 공개 심사 경로 ──

    @Transactional
    public void requestPublish(String username, Long characterId, boolean cancel) {
        Character character = ownedUgc(username, characterId);
        if (cancel) {
            character.cancelPublishRequest();
        } else {
            character.requestPublish();
        }
        log.info("[UGC] 공개 신청 {}: characterId={}, username={}", cancel ? "취소" : "접수", characterId, username);
    }

    // ── Secret 단독 심사 경로 (2026-07-17 결정 — PRIVATE 유지 캐릭터도 신청 가능) ──

    @Transactional
    public void requestSecretReview(String username, Long characterId) {
        Character character = ownedUgc(username, characterId);
        character.requestSecretReview();
        log.info("[UGC] Secret 심사 신청: characterId={}, username={}", characterId, username);
    }

    // ── 완성 화면 인라인 텍스트 수정 (에셋 무관 — 무료) ──

    @Transactional
    public void updateTexts(String username, Long characterId, UgcDtos.UpdateTextsRequest req) {
        Character character = ownedUgc(username, characterId);
        character.updateUgcTexts(req.name(), req.tagline(), req.personality(), req.tone(), req.firstGreeting());
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
