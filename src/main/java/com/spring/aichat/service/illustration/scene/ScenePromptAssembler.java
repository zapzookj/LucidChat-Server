package com.spring.aichat.service.illustration.scene;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * [2026-07-30 B-2/A-1 재피벗] 씬 일러 프롬프트 조립기 — 디오라마 검증본 개별 포팅
 * (L1 다중인물 규약 + TIPO 모드 분리, docs/09 §A-1 프롬프트 규약).
 *
 * <p><b>태그 검수기 금지 원칙(docs/09 §B-1)</b>: 태그 재작성 개입(정본화·오타교정·드랍)은
 * 전제 오류로 실증 폐기 — danbooru 사전 ≠ 모델 어휘(CLIP은 자연어도 읽음). LLM 산출 태그는
 * <b>무가공 통과</b>하며 여기서는 구조(모드·블록·순서)만 책임진다:
 * <ul>
 *   <li><b>1인/무인물 씬</b> ({@code tipoFull=true}): 플랫 전체 프롬프트를 TIPO에 통째로 —
 *       PoC 검증 경로. ban = 인원수 모순 태그.</li>
 *   <li><b>다중 씬</b> ({@code tipoFull=false}): TIPO는 <b>씬 레이어(품질+카운트+행위+장소)만</b>
 *       업샘플(ban = 외형 정의 태그) → 캐릭터 블록은 ConditioningConcat으로 원본 결합.</li>
 * </ul>
 * 블록 규약: counts 우선 → 상호작용(공유) → 성별 앵커 블록(히로인 먼저·유저 faceless 마지막,
 * {@code (…:1.1)}) → 장소. 화면 내 총원≤2 제약은 LLM 프롬프트 지시가 담당(구조적 한계의 기획 반영).
 */
@Component
public class ScenePromptAssembler {

    private static final String QUALITY_PREFIX =
        "masterpiece, best quality, newest, absurdres, highly detailed, anime style";
    private static final double BLOCK_WEIGHT = 1.1;

    /** 1인 풀-TIPO 모드 밴(PoC 검증 리스트 기반) — 인원수 모순·비일러 포맷. */
    private static final String SINGLE_MODE_BAN =
        "multiple girls, 2girls, 3girls, multiple boys, 2boys, multiple views, comic, monochrome, greyscale";

    /** 무인물 씬 밴 — 인물 태그 전면 차단. */
    private static final String SCENERY_BAN =
        "1girl, 1boy, 2girls, 2boys, multiple girls, multiple boys, solo";

    /** [2026-07-30 리뷰픽스] 비시크릿 방 수위 게이트 — TIPO가 NSFW 태그를 증식하지 못하게 차단. */
    private static final String SFW_BAN =
        "nsfw, explicit, nude, nudity, topless, bottomless, sex, nipples, pussy, penis, cum";

    /**
     * 다중 모드 밴 — TIPO가 씬 레이어에 외형 정의 태그를 추가하면 블록 밖에서 오염이 재발하므로
     * 머리/눈 색·헤어스타일류를 차단(캐릭터 외형은 블록 전담). 튜닝 가능 상수.
     */
    private static final String MULTI_MODE_BAN =
        "solo, blonde hair, black hair, brown hair, pink hair, blue hair, silver hair, grey hair, "
            + "white hair, red hair, purple hair, green hair, orange hair, aqua hair, multicolored hair, "
            + "streaked hair, long hair, short hair, very long hair, medium hair, twintails, ponytail, "
            + "braid, ahoge, blue eyes, red eyes, green eyes, brown eyes, purple eyes, yellow eyes, "
            + "pink eyes, grey eyes, black eyes, orange eyes, heterochromia";

    /**
     * 씬 등장 인물 1명.
     *
     * @param male 남성 여부(LLM cast.gender — 미지정 히로인은 여성, 유저는 남성 기본)
     */
    public record SceneActor(String appearanceTags, String emotion, String pose, boolean user, boolean male) {}

    /**
     * 조립 산출.
     *
     * @param sceneTags   TIPO 입력(1인=전체 플랫 프롬프트 / 다중=씬 레이어)
     * @param actorBlocks 다중 모드 캐릭터 블록(ConditioningConcat 대상) — 1인/무인물이면 빈 리스트
     * @param banTags     TIPO ban_tags
     * @param tipoFull    전체 프롬프트를 TIPO에 통째로 넣는 모드 여부
     * @param fullPrompt  영속/디버그용 전체 문자열
     */
    public record ScenePrompt(String sceneTags, List<String> actorBlocks, String banTags,
                              boolean tipoFull, String fullPrompt) {}

    /** 하위 호환 오버로드 — sfw 기본값 true (안전 기본). */
    public ScenePrompt assemble(String locationDescription, String actionDescription, List<SceneActor> actors) {
        return assemble(locationDescription, actionDescription, actors, true);
    }

    /**
     * @param sfw [2026-07-30 리뷰픽스 수위 게이트] 비시크릿 방 강제 — 씬 레이어에 {@code sfw} 태그
     *            + TIPO ban에 NSFW 계열 추가. 태그 검수기 금지 원칙(무가공)은 LLM 태그 재작성 금지가
     *            요지 — 이건 재작성이 아니라 콘텐츠 정책 레이어의 추가 제약이다.
     *            시크릿 방(SecretModeService 통과)만 false.
     */
    public ScenePrompt assemble(String locationDescription, String actionDescription,
                                List<SceneActor> actors, boolean sfw) {
        String action = cleanCsv(actionDescription);
        String location = cleanCsv(locationDescription);
        String ratingTag = sfw ? "sfw" : "";
        String ratingBan = sfw ? ", " + SFW_BAN : "";

        long girls = actors.stream().filter(a -> !a.male()).count();
        long boys = actors.stream().filter(SceneActor::male).count();

        // ── 무인물 씬: 배경 전용 풀-TIPO ──
        if (actors.isEmpty()) {
            String tags = joinNonBlank(QUALITY_PREFIX, ratingTag, "scenery, no humans", action, location);
            return new ScenePrompt(tags, List.of(), SCENERY_BAN + ratingBan, true, tags);
        }

        String counts = joinNonBlank(countTag(girls, "girl"), countTag(boys, "boy"));

        // ── 1인 씬: 플랫 전체 프롬프트 풀-TIPO (PoC 경로 — 블록/가중치 없이) ──
        if (actors.size() == 1) {
            SceneActor a = actors.get(0);
            String identity = a.user()
                ? userIdentity(a)
                : cleanCsv(a.appearanceTags());
            String tags = joinNonBlank(QUALITY_PREFIX, ratingTag, counts, action, identity,
                cleanCsv(a.emotion()), cleanCsv(a.pose()), location);
            String ban = SINGLE_MODE_BAN + (a.male() ? ", 1girl" : ", 1boy") + ratingBan;
            return new ScenePrompt(tags, List.of(), ban, true, tags);
        }

        // ── 다중 씬: 씬 레이어(TIPO) + 캐릭터 블록(Concat) ──
        String sceneLayer = joinNonBlank(QUALITY_PREFIX, ratingTag, counts, action, location);

        List<SceneActor> ordered = new ArrayList<>();
        actors.stream().filter(a -> !a.user()).forEach(ordered::add);
        actors.stream().filter(SceneActor::user).forEach(ordered::add);

        List<String> blocks = new ArrayList<>();
        for (SceneActor a : ordered) {
            String block = buildActorBlock(a);
            if (!block.isBlank()) blocks.add("(" + block + ":" + BLOCK_WEIGHT + ")");
        }

        String full = sceneLayer + (blocks.isEmpty() ? "" : ", " + String.join(", ", blocks));
        return new ScenePrompt(sceneLayer, blocks, MULTI_MODE_BAN + ratingBan, false, full);
    }

    private String buildActorBlock(SceneActor a) {
        List<String> t = new ArrayList<>();
        t.add(a.male() ? "1boy" : "1girl");
        if (a.user()) {
            t.add(userIdentity(a));
        } else {
            addIf(t, cleanCsv(a.appearanceTags()));
        }
        addIf(t, cleanCsv(a.emotion()));
        addIf(t, cleanCsv(a.pose()));
        return String.join(", ", t);
    }

    /**
     * 유저 정체성 태그 — PoC 실측(2026-07-29, 36장): {@code faceless male} 단독은 밀착 씬 12/12에서
     * 얼굴이 그려짐(죽은 태그). {@code head out of frame}이 몸은 유지하고 얼굴만 프레임 밖으로
     * 밀어내는 실효 태그라 병기한다. 유저 얼굴 일관성은 보장 불가 전제(docs/09 앵글 정책).
     *
     * <p>[2026-08-04 폴리싱] 성인 앵커 병기 — illustrious의 1boy=소년 편향으로 SANDBOX 씬에
     * '어린 남자애'가 그려지는 실관찰 대응(종원). 워크플로 네거티브의 연령 밴과 이중 방어.
     */
    private static String userIdentity(SceneActor a) {
        return (a.male() ? "faceless male, mature male, adult" : "faceless female, adult")
            + ", head out of frame";
    }

    private static String countTag(long n, String noun) {
        if (n <= 0) return "";
        if (n == 1) return "1" + noun;
        if (n >= 6) return "6+" + noun + "s";
        return n + noun + "s";
    }

    private static String joinNonBlank(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) addIf(out, p);
        return String.join(", ", out);
    }

    private static void addIf(List<String> list, String s) {
        if (s != null && !s.isBlank()) list.add(s.trim());
    }

    /**
     * 무가공 정리 — 재작성 없음: 마침표/개행→콤마 분리, 트림, 공백 정규화, 중복 제거만.
     * (LLM이 고른 표현은 그대로 통과 — 렉시콘 롤백 원칙, docs/09 §B-1)
     */
    static String cleanCsv(String s) {
        if (s == null || s.isBlank()) return "";
        String pre = s.replaceAll("[\\r\\n]+", ", ").replaceAll("[.。]+", ", ");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : pre.split(",")) {
            String trimmed = t.trim().replaceAll("\\s{2,}", " ");
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return String.join(", ", out);
    }
}
