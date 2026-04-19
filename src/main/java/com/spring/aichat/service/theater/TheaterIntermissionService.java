package com.spring.aichat.service.theater;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.theater.IntermissionCatalog;
import com.spring.aichat.domain.theater.TheaterDirectorNote;
import com.spring.aichat.domain.theater.TheaterDirectorNoteRepository;
import com.spring.aichat.domain.theater.TheaterHeroineAffection;
import com.spring.aichat.domain.theater.TheaterHeroineAffectionRepository;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.theater.TheaterStateRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.TheaterResponses.IntermissionActivity;
import com.spring.aichat.dto.theater.TheaterResponses.IntermissionResult;
import com.spring.aichat.dto.theater.TheaterResponses.IntermissionView;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * [Phase 5.5-Theater] 인터미션 서비스
 *
 * Act 사이의 스탯 성장 미니게임.
 * 피로도 5 + 추가 활동은 에너지 2 소모.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterIntermissionService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterDirectorNoteRepository directorNoteRepository;
    private final UserRepository userRepository;

    private final Random random = new Random();

    @Transactional
    public IntermissionView getIntermissionView(Long roomId, String username) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (!state.isInIntermission()) {
            throw new BadRequestException("현재 인터미션이 아닙니다.");
        }

        List<IntermissionActivity> activities = rollActivitiesForThisIntermission();

        Map<String, Integer> currentStats = new LinkedHashMap<>();
        for (AvatarStat stat : AvatarStat.values()) {
            currentStats.put(stat.name(), state.getStat(stat));
        }

        return new IntermissionView(
            state.getIntermissionStamina(),
            ChatModePolicy.INTERMISSION_STAMINA_MAX,
            currentStats, activities, List.of()
        );
    }

    private List<IntermissionActivity> rollActivitiesForThisIntermission() {
        List<IntermissionActivity> result = new ArrayList<>(IntermissionCatalog.BASE_ACTIVITIES);

        if (random.nextDouble() < IntermissionCatalog.SPECIAL_ACTIVITY_PROBABILITY) {
            int replaceCount = 1 + random.nextInt(IntermissionCatalog.SPECIAL_REPLACE_MAX);
            List<IntermissionActivity> specials = new ArrayList<>(IntermissionCatalog.SPECIAL_ACTIVITIES);
            Collections.shuffle(specials, random);

            List<Integer> indicesToReplace = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) indicesToReplace.add(i);
            Collections.shuffle(indicesToReplace, random);

            for (int i = 0; i < Math.min(replaceCount, specials.size()); i++) {
                result.set(indicesToReplace.get(i), specials.get(i));
            }
        }
        return result;
    }

    @Transactional
    public IntermissionResult performActivity(Long roomId, String username,
                                              String activityId, boolean useExtraEnergy) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (!state.isInIntermission()) {
            throw new BadRequestException("현재 인터미션이 아닙니다.");
        }

        IntermissionActivity activity = IntermissionCatalog.findById(activityId)
            .orElseThrow(() -> new BadRequestException("존재하지 않는 활동입니다: " + activityId));

        boolean staminaExhausted = state.getIntermissionStamina() <= 0;
        if (staminaExhausted) {
            if (!useExtraEnergy) {
                throw new BadRequestException("피로도가 바닥났습니다. 추가 에너지 사용이 필요합니다.");
            }
            User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
            user.consumeEnergy(ChatModePolicy.INTERMISSION_EXTRA_ENERGY_COST);
        } else {
            state.consumeIntermissionStamina();
        }

        if (activity.special()) {
            return performSpecialActivity(room, state, activity);
        }

        AvatarStat targetStat = AvatarStat.valueOf(activity.targetStat());
        int currentValue = state.getStat(targetStat);
        String outcome = rollOutcome(currentValue);
        int delta = IntermissionCatalog.getStatDelta(outcome);

        state.applyStatChange(targetStat, delta);
        int newValue = state.getStat(targetStat);

        String narration = IntermissionCatalog.getResultNarration(outcome, targetStat);

        if ("GREAT_SUCCESS".equals(outcome)) {
            directorNoteRepository.save(TheaterDirectorNote.intermission(
                room,
                String.format("대성공! %s이(가) %d → %d. %s",
                    targetStat.getDisplayName(), currentValue, newValue, activity.title()),
                state.getCurrentAct().getNumber()
            ));
        }

        log.info("🎭 [INTERMISSION] Activity done | roomId={} | activity={} | outcome={} | {}→{}",
            roomId, activityId, outcome, currentValue, newValue);

        return new IntermissionResult(
            activityId, outcome, targetStat.name(),
            delta, newValue, state.getIntermissionStamina(),
            narration, state.getIntermissionStamina() <= 0
        );
    }

    private String rollOutcome(int currentStatValue) {
        Map<String, Integer> dist = IntermissionCatalog.getSuccessDistribution(currentStatValue);
        int roll = random.nextInt(100);
        int cum = 0;
        for (var entry : dist.entrySet()) {
            cum += entry.getValue();
            if (roll < cum) return entry.getKey();
        }
        return "FAIL";
    }

    private IntermissionResult performSpecialActivity(ChatRoom room, TheaterState state,
                                                      IntermissionActivity activity) {
        return switch (activity.id()) {
            case "special_encounter" -> handleEncounter(room, state);
            case "special_stranger" -> handleStranger(room, state);
            case "special_mirror" -> handleMirror(room, state);
            case "special_letter" -> handleLetter(room, state);
            case "special_dream" -> handleDream(room, state);
            default -> new IntermissionResult(
                activity.id(), "SUCCESS", null, 0, 0,
                state.getIntermissionStamina(),
                "특별한 시간이 지나갔다.",
                state.getIntermissionStamina() <= 0
            );
        };
    }

    private IntermissionResult handleEncounter(ChatRoom room, TheaterState state) {
        List<TheaterHeroineAffection> affections = affectionRepository
            .findByRoomOrderByAffectionDesc(room.getId());
        if (!affections.isEmpty()) {
            TheaterHeroineAffection top = affections.get(0);
            top.applyDelta(3);
            return new IntermissionResult(
                "special_encounter", "SUCCESS", null, 0, 0,
                state.getIntermissionStamina(),
                String.format("💫 %s과(와) 우연히 마주쳤다. 짧은 말 몇 마디가 남았다. 호감도 +3",
                    top.getCharacter().getName()),
                state.getIntermissionStamina() <= 0
            );
        }
        return neutralResult("special_encounter", state);
    }

    private IntermissionResult handleStranger(ChatRoom room, TheaterState state) {
        AvatarStat[] all = AvatarStat.values();
        AvatarStat a = all[random.nextInt(all.length)];
        AvatarStat b;
        do { b = all[random.nextInt(all.length)]; } while (b == a);
        state.applyStatChange(a, 1);
        state.applyStatChange(b, 1);
        return new IntermissionResult(
            "special_stranger", "SUCCESS", null, 0, 0,
            state.getIntermissionStamina(),
            String.format("👥 낯선 이와의 짧은 대화. 무언가 배웠다. %s +1, %s +1",
                a.getDisplayName(), b.getDisplayName()),
            state.getIntermissionStamina() <= 0
        );
    }

    private IntermissionResult handleMirror(ChatRoom room, TheaterState state) {
        AvatarStat[] all = AvatarStat.values();
        AvatarStat pick = all[random.nextInt(all.length)];
        state.applyStatChange(pick, 1);
        directorNoteRepository.save(TheaterDirectorNote.intermission(
            room,
            "거울 앞의 긴 시간. 주인공은 이번 Chapter의 자신을 돌아보았다.",
            state.getCurrentAct().getNumber()
        ));
        return new IntermissionResult(
            "special_mirror", "SUCCESS", pick.name(),
            1, state.getStat(pick), state.getIntermissionStamina(),
            String.format("🪞 거울 속 자신과 마주했다. %s +1", pick.getDisplayName()),
            state.getIntermissionStamina() <= 0
        );
    }

    private IntermissionResult handleLetter(ChatRoom room, TheaterState state) {
        AvatarStat stat = AvatarStat.EMPATHY;
        int roll = random.nextInt(100);
        String outcome;
        if (roll < 25) outcome = "GREAT_SUCCESS";
        else if (roll < 85) outcome = "SUCCESS";
        else outcome = "FAIL";

        int delta = IntermissionCatalog.getStatDelta(outcome);
        state.applyStatChange(stat, delta);

        return new IntermissionResult(
            "special_letter", outcome, stat.name(),
            delta, state.getStat(stat), state.getIntermissionStamina(),
            String.format("✉️ 편지를 쓰며 감정이 정리되었다. %s",
                IntermissionCatalog.getResultNarration(outcome, stat)),
            state.getIntermissionStamina() <= 0
        );
    }

    private IntermissionResult handleDream(ChatRoom room, TheaterState state) {
        AvatarStat[] all = AvatarStat.values();
        AvatarStat a = all[random.nextInt(all.length)];
        AvatarStat b;
        do { b = all[random.nextInt(all.length)]; } while (b == a);
        state.applyStatChange(a, 1);
        state.applyStatChange(b, 1);
        return new IntermissionResult(
            "special_dream", "SUCCESS", null, 0, 0,
            state.getIntermissionStamina(),
            String.format("💤 꿈결에서 무언가 달라진 느낌. %s +1, %s +1",
                a.getDisplayName(), b.getDisplayName()),
            state.getIntermissionStamina() <= 0
        );
    }

    private IntermissionResult neutralResult(String id, TheaterState state) {
        return new IntermissionResult(
            id, "SUCCESS", null, 0, 0,
            state.getIntermissionStamina(),
            "조용한 시간이 흘러갔다.",
            state.getIntermissionStamina() <= 0
        );
    }

    @Transactional
    public void finishIntermission(Long roomId, String username) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);
        if (!state.isInIntermission()) {
            throw new BadRequestException("현재 인터미션이 아닙니다.");
        }
        state.endIntermission();
        log.info("🎭 [INTERMISSION] Finished | roomId={}", roomId);
    }

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        return room;
    }

    private TheaterState getState(Long roomId) {
        return theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션이 없습니다."));
    }
}