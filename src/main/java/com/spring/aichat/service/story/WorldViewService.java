package com.spring.aichat.service.story;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldLocation;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldLocation;
import com.spring.aichat.domain.world.WorldLocationRepository;
import com.spring.aichat.domain.world.WorldRef;
import com.spring.aichat.domain.world.WorldRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [2026-07-31 에픽 A] WorldView 로더 — 공식/UGC 월드를 단일 뷰로 해소.
 *
 * <p>접근 정책({@link #assertUgcPlayable}): 소유 월드는 항상 플레이 가능,
 * 타인 월드는 검수 APPROVED일 때만(캐릭터 접근권은 호출측이 isAccessibleBy로 별도 필터 —
 * 월드 재잠금(reviewStatus NONE 리셋)·캐릭터 철회 모두 이 두 겹에서 차단된다).
 */
@Service
@RequiredArgsConstructor
public class WorldViewService {

    private final WorldRepository worldRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final UgcWorldRepository ugcWorldRepository;
    private final UgcWorldLocationRepository ugcWorldLocationRepository;

    /** ref → 뷰 로드. 존재 검증만 — active/승인 게이트는 호출 맥락별(생성 vs 기존 방 열람)로 별도. */
    @Transactional(readOnly = true)
    public WorldView resolve(WorldRef ref) {
        if (ref.isUgc()) {
            UgcWorld ugc = ugcWorldRepository.findById(ref.ugcWorldId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WORLD_NOT_FOUND,
                    "UGC world not found: " + ref.key()));
            return new WorldView(ref, null, ugc, ugcLocations(ugc.getId()));
        }
        World world = worldRepository.findById(ref.officialId())
            .orElseThrow(() -> new BusinessException(ErrorCode.WORLD_NOT_FOUND,
                "World not found: " + ref.key()));
        return fromOfficial(world);
    }

    /** 방 → 뷰. STORY가 아니거나 월드 참조가 없으면 null. */
    @Transactional(readOnly = true)
    public WorldView resolveForRoom(ChatRoom room) {
        if (room.getUgcWorldId() != null) {
            return resolve(WorldRef.ofUgc(room.getUgcWorldId()));
        }
        if (room.getWorld() != null) {
            return fromOfficial(room.getWorld());
        }
        return null;
    }

    /** 이미 로드된 공식 World 엔티티로 뷰 구성 (재조회 회피). */
    @Transactional(readOnly = true)
    public WorldView fromOfficial(World world) {
        List<WorldView.LocationView> locations = worldLocationRepository
            .findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(world.getId()).stream()
            .map(WorldViewService::toView)
            .toList();
        return new WorldView(WorldRef.ofOfficial(world.getId()), world, null, locations);
    }

    /**
     * UGC 월드 플레이 자격 — 소유자는 무조건, 타인은 검수 APPROVED만.
     * (수정 시 reviewStatus가 NONE으로 리셋되는 재잠금 구조와 맞물려, 미검수 수정본이
     * 타 유저 스토리에 노출되는 경로를 차단한다.)
     */
    public void assertUgcPlayable(WorldView view, User user) {
        if (!view.isUgc()) return;
        UgcWorld ugc = view.ugc();
        if (ugc.isOwnedBy(user.getId())) return;
        if (ugc.getReviewStatus() != com.spring.aichat.domain.ugc.WorldReviewStatus.APPROVED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 세계관은 아직 플레이할 수 없어요.");
        }
    }

    private List<WorldView.LocationView> ugcLocations(Long ugcWorldId) {
        return ugcWorldLocationRepository
            .findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(ugcWorldId).stream()
            .map(WorldViewService::toView)
            .toList();
    }

    private static WorldView.LocationView toView(WorldLocation l) {
        return new WorldView.LocationView(l.getLocationKey(), l.getDisplayName(),
            l.getDescription(), Boolean.TRUE.equals(l.getSelectableAsStart()), null);
    }

    /** UGC 장소는 전부 시작 가능(공식의 selectableAsStart 큐레이션 개념 없음 — MVP). */
    private static WorldView.LocationView toView(UgcWorldLocation l) {
        return new WorldView.LocationView(l.getLocationKey(), l.getDisplayName(),
            l.getDescription(), true, l.getBackgroundUrl());
    }
}
