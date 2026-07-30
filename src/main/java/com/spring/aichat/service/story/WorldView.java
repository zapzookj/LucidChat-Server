package com.spring.aichat.service.story;

import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldRef;

import java.util.List;
import java.util.Optional;

/**
 * [2026-07-31 에픽 A] 월드 메타 추상 — enum PK 브리지의 서비스 계층 단일 인터페이스.
 *
 * <p>V2 STORY의 실질 결합부(디렉터 프롬프트 [2] WORLD 섹션·장소 풀·CreateFlow·방 상세)가
 * 공식 World(WorldId enum PK + WorldLocation)와 UgcWorld(Long PK + UgcWorldLocation)를
 * 구분 없이 소비하도록 한다. 라우팅·프레즌스·기억은 room+characterId+locationKey 스코프라
 * 이 추상 없이도 이미 월드 무관(전수 조사 실측).
 *
 * <p>수위 정책: UGC 월드는 {@link #secretAllowed()} 항상 false(종원 확정 — 시크릿 게이팅은
 * 공식 월드 메타 전용). 전면 Long PK 전환(B안) 시 이 추상이 전환 비용을 선흡수한다.
 */
public record WorldView(
    WorldRef ref,
    World official,
    UgcWorld ugc,
    List<LocationView> locations
) {

    /** 장소 뷰 — WorldLocation/UgcWorldLocation 공통 투영. backgroundUrl은 UGC 대표 배경만 보유. */
    public record LocationView(String key, String displayName, String description,
                               boolean selectableAsStart, String backgroundUrl) {}

    public boolean isUgc() {
        return ugc != null;
    }

    public String displayName() {
        return isUgc() ? ugc.getName() : official.getDisplayName();
    }

    public String tagline() {
        return isUgc() ? ugc.getIntro() : official.getTagline();
    }

    /** 설정 본문 — 공식 description / UGC lore(유저 생성 텍스트 — 프롬프트 주입 시 캡슐화 필수). */
    public String description() {
        return isUgc() ? ugc.getLore() : official.getDescription();
    }

    public String moodKeywords() {
        return isUgc() ? ugc.getMoodTags() : official.getMoodKeywords();
    }

    public String thumbnailUrl() {
        return isUgc() ? ugc.getThumbnailUrl() : official.getThumbnailUrl();
    }

    public String heroImageUrl() {
        return isUgc() ? ugc.getThumbnailUrl() : official.getHeroImageUrl();
    }

    /** UGC 월드는 시크릿 불허(확정 정책) — 공식만 월드 메타를 따른다. */
    public boolean secretAllowed() {
        return !isUgc() && official.isSecretAllowed();
    }

    /** 배경 캐시 결정론 키 — 공식 {@code WORLD__KEY} / UGC {@code UGCW_{id}__KEY}(기존 네임스페이스). */
    public String canonicalKey(String locationKey) {
        return ref.key() + "__" + locationKey;
    }

    public Optional<LocationView> location(String key) {
        if (key == null) return Optional.empty();
        return locations.stream().filter(l -> l.key().equals(key)).findFirst();
    }

    public List<LocationView> startableLocations() {
        return locations.stream().filter(LocationView::selectableAsStart).toList();
    }

    public String locationDisplayName(String key) {
        return location(key).map(LocationView::displayName).orElse(key);
    }
}
