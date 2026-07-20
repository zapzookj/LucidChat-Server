package com.spring.aichat.service.ugc;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterSource;
import com.spring.aichat.domain.enums.CharacterVisibility;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.UgcDtos;
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
