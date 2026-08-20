package com.spring.aichat.service;

import com.spring.aichat.config.LegacyFeatureProperties;
import com.spring.aichat.domain.achievement.Achievement;
import com.spring.aichat.domain.achievement.AchievementRepository;
import com.spring.aichat.domain.enums.EasterEggType;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.achievement.AchievementResponse;
import com.spring.aichat.dto.achievement.AchievementResponse.AchievementItem;
import com.spring.aichat.dto.achievement.AchievementResponse.Gallery;
import com.spring.aichat.dto.achievement.AchievementResponse.UnlockNotification;
import com.spring.aichat.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [Phase 4.4] Achievement Service
 *
 * 업적 해금, 조회, 갤러리 데이터 구성
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AchievementService {

    private final AchievementRepository achievementRepository;
    private final LegacyFeatureProperties legacy;
    private final UserRepository userRepository;

    // ── 전체 업적 정의 (미해금 힌트 표시용) ──
    private static final List<AchievementItem> ALL_ACHIEVEMENTS = List.of(
        // Endings
        new AchievementItem("ENDING", "HAPPY_ENDING", "Happily Ever After", "해피엔딩",
            "행복한 결말을 맞이했다.", "💕", null),
        new AchievementItem("ENDING", "BAD_ENDING", "Bittersweet Farewell", "배드엔딩",
            "이별의 결말을 맞이했다.", "💔", null),
        // Special (Easter Eggs)
        new AchievementItem("SPECIAL", "STOCKHOLM", "Stockholm Syndrome", "스톡홀름 증후군",
            "???", "🖤", null),
        new AchievementItem("SPECIAL", "DRUNK", "The Drunk", "만취",
            "???", "🍷", null),
        new AchievementItem("SPECIAL", "FOURTH_WALL", "Hacker", "해커",
            "???", "💻", null),
        new AchievementItem("SPECIAL", "MACHINE_REBELLION", "Machine Rebellion", "기계의 반란",
            "???", "🤖", null),
        new AchievementItem("SPECIAL", "INVISIBLE_MAN", "The Watcher", "투명인간",
            "???", "👁️", null)
    );

    /**
     * 이스터에그 업적 해금
     * @return UnlockNotification (isNew=false면 이미 보유)
     */
    @Transactional
    public UnlockNotification unlockEasterEgg(Long userId, EasterEggType eggType) {
        // [블록 D · docs/14 §C#6] 업적 게이트 오프 — 지급만 건너뛴다.
        //   이스터에그 *연출*은 유지 확정이므로 호출부(ChatStreamService.processEasterEgg)는
        //   계속 EasterEggEvent를 내려보내고 achievement 필드만 null이 된다(FE는 옵셔널 체이닝).
        if (!legacy.getAchievement().isEnabled()) return null;
        String code = eggType.name();

        if (achievementRepository.existsByUserIdAndCode(userId, code)) {
            log.info("🏆 [ACHIEVEMENT] Already unlocked: {} | userId={}", code, userId);
            return new UnlockNotification(
                code, eggType.getTitle(), eggType.getTitleKo(),
                eggType.getDescription(), eggType.getIcon(), false
            );
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        Achievement achievement = Achievement.easterEgg(user, eggType);
        achievementRepository.save(achievement);

        log.info("🏆 [ACHIEVEMENT] NEW unlock: {} | userId={}", code, userId);
        return new UnlockNotification(
            code, eggType.getTitle(), eggType.getTitleKo(),
            eggType.getDescription(), eggType.getIcon(), true
        );
    }

    /**
     * 엔딩 업적 해금
     */
    @Transactional
    public UnlockNotification unlockEnding(Long userId, String endingType) {
        if (!legacy.getAchievement().isEnabled()) return null;   // [블록 D · docs/14 §C#6]
        String code = endingType + "_ENDING";

        if (achievementRepository.existsByUserIdAndCode(userId, code)) {
            log.info("🏆 [ACHIEVEMENT] Ending already unlocked: {} | userId={}", code, userId);
            return null;
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        Achievement achievement = Achievement.ending(user, endingType);
        achievementRepository.save(achievement);

        log.info("🏆 [ACHIEVEMENT] Ending unlock: {} | userId={}", code, userId);
        return new UnlockNotification(
            code, achievement.getTitle(), achievement.getTitleKo(),
            achievement.getDescription(), achievement.getIcon(), true
        );
    }

    /**
     * 업적 갤러리 조회 — 해금/미해금 분리
     */
    @Transactional(readOnly = true)
    public Gallery getGallery(Long userId) {
        // [블록 D · docs/14 §C#6] 갤러리 게이트 오프 — 예외가 아니라 빈 갤러리를 준다.
        //   보관함 탭이 이 응답을 렌더하므로 던지면 탭 전체가 깨진다.
        if (!legacy.getAchievement().isEnabled()) return new Gallery(List.of(), List.of(), 0, 0);
        List<Achievement> userAchievements = achievementRepository.findByUserIdOrderByUnlockedAtDesc(userId);

        Set<String> unlockedCodes = userAchievements.stream()
            .map(Achievement::getCode)
            .collect(Collectors.toSet());

        List<AchievementItem> unlocked = userAchievements.stream()
            .map(a -> new AchievementItem(
                a.getType(), a.getCode(), a.getTitle(), a.getTitleKo(),
                a.getDescription(), a.getIcon(), a.getUnlockedAt()
            ))
            .collect(Collectors.toList());

        List<AchievementItem> locked = new ArrayList<>();
        for (AchievementItem def : ALL_ACHIEVEMENTS) {
            if (!unlockedCodes.contains(def.code())) {
                locked.add(def);
            }
        }

        return new Gallery(unlocked, locked, ALL_ACHIEVEMENTS.size(), unlocked.size());
    }

    /**
     * 클라이언트 트리거 이스터에그 해금 (프론트에서 직접 호출)
     * INVISIBLE_MAN 등
     */
    @Transactional
    public UnlockNotification unlockClientTriggered(Long userId, String easterEggCode) {
        // [블록 D · docs/14 §C#6 + docs/13 B-7] 게이트 오프.
        //   부수적으로 '조건 검증 없는 자가 해금' 착취면(방 ID + 코드 문자열만으로 즉시 해금)도 닫힌다.
        if (!legacy.getAchievement().isEnabled()) return null;
        try {
            EasterEggType eggType = EasterEggType.valueOf(easterEggCode);
            if (eggType.isLlmTriggered()) {
                log.warn("🏆 [ACHIEVEMENT] Attempted client unlock of LLM-triggered egg: {}", easterEggCode);
                return null;
            }
            return unlockEasterEgg(userId, eggType);
        } catch (IllegalArgumentException e) {
            log.warn("🏆 [ACHIEVEMENT] Unknown easter egg code: {}", easterEggCode);
            return null;
        }
    }
}