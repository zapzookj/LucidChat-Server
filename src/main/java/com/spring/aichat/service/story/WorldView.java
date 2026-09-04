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

    /**
     * [E-3.②.14] 위치 키 → 사람이 읽는 이름. <b>미선언 키의 폴백 규칙을 여기 한 곳에만 둔다.</b>
     *
     * <p>종전엔 프롬프트 조립부와 방 상세 DTO가 각자 {@code orElse(locationKey)}로 폴백해
     * <b>영문 SCREAMING_SNAKE 토큰이 그대로 새어 나갔다</b> — 한국어 프롬프트 안에 맨몸으로 박히고,
     * {@code currentUserLocationDisplayName}을 통해 <b>채팅 헤더에도 그대로 렌더</b>됐다
     * ({@code StoryV2Header.jsx}·{@code StoryV2TopIndicator.jsx}).
     *
     * <p>폴백 순서: ① 선언된 장소의 표시명 ② 방이 들고 있는 동적 장소 이름
     * ({@code ChatRoom.currentDynamicLocationName} — 디렉터가 {@code new_dynamic_location}으로
     * 세팅하는 사람이 읽을 이름) ③ 중립 카피. <b>raw 키는 어느 경우에도 표시하지 않는다</b> —
     * 원문은 로그에만 남긴다(운영 중 탐지가 목적이다).
     *
     * <p>★ [적대적 검토 반영] 폴백 ②는 <b>키가 실제로 그 동적 장소일 때만</b> 쓴다.
     * 종전엔 대조 없이 {@code dynamicName}을 반환해, 아무 관계 없는 미선언 키까지 방의 동적 장소
     * 이름으로 <b>자신 있게 오표기</b>했다 — raw 키를 보여주던 시절보다 나쁘다(그땐 오탐을 알아챌 수 있었다).
     * 특히 동적 장소 A에 있던 캐릭터가 남은 채 디렉터가 B를 만들면, 그 캐릭터가 B의 이름으로 표시됐다.
     *
     * @param dynamicName        방의 현재 동적 장소 이름. 없으면 null.
     * @param dynamicCanonicalKey 그 동적 장소의 키 — {@code locationKey}와 일치할 때만 이름을 쓴다.
     */
    public static String resolveLocationDisplay(String locationKey,
                                                List<LocationView> locations,
                                                String dynamicName,
                                                String dynamicCanonicalKey) {
        if (locationKey == null || locationKey.isBlank()) return "(위치 미상)";
        for (LocationView l : locations) {
            if (l.key().equals(locationKey)) return l.displayName();
        }
        if (dynamicName != null && !dynamicName.isBlank()
            && locationKey.equals(dynamicCanonicalKey)) {
            return dynamicName;
        }
        return "(임시 장소)";
    }

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
