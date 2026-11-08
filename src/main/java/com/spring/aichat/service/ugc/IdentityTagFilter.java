package com.spring.aichat.service.ugc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [docs/56 §10] 태거 출력 → 정체성(외형) 태그. PoC 필터(poc/ref_tagger.py)의 자바판 + PoC 관측 보강.
 *
 * <p>남기는 것: 머리·눈·체형·복장·액세서리·고유 특징. 버리는 것은 네 갈래다.
 * <ul>
 *   <li>씬마다 따로 정해지거나 리파인이 금지하는 것 — 구도·자세·표정·배경·소품.</li>
 *   <li><b>신체 강조</b> — {@code thigh gap}·{@code covered navel} 등. WF-2가 이 태그로 하반신을 강조해 다시 그리면
 *       감정 컷 GPT 편집이 정책 차단에 걸렸다(docs/56 §3: 7/12 → 제거 후 12/12).</li>
 *   <li><b>메타</b> — {@code enmaided}·{@code alternate costume}·{@code cosplay}(§5 관측).</li>
 *   <li>캐릭터 고유명(태거 character 카테고리) — 타 작품 IP 이름이 프롬프트에 박히지 않게(짧은 영문 이름 태그는
 *       카테고리 정보가 없는 노드 출력이라 '괄호 작품명' 패턴으로 거른다).</li>
 * </ul>
 * 노드는 확률을 주지 않으므로 순서는 태그 사전 순서 그대로다.
 */
public final class IdentityTagFilter {
    private IdentityTagFilter() {}

    static final Set<String> DROP_EXACT = Set.of(
        "1girl", "1boy", "solo", "looking at viewer", "standing", "full body", "cowboy shot", "upper body",
        "simple background", "white background", "grey background", "gradient background", "transparent background",
        "closed mouth", "open mouth", "parted lips", "smile", "light smile", "expressionless", "blush", ":o", ":|",
        "hand up", "arm up", "arms up", "hand on own face", "hand on own cheek", "hand in own hair", "hand on own hip",
        "arm at side", "arms at sides", "feet out of frame", "legs together", "contrapposto", "standing on one leg",
        "from side", "from below", "from above", "looking to the side", "looking away", "head tilt", "sidelighting",
        "hand to own mouth", "finger to mouth", "crossed legs", "knees together feet apart", "skirt hold",
        // 신체 강조 (docs/56 §3)
        "thigh gap", "covered navel", "collarbone", "cleavage", "navel", "groin", "crotch", "ass", "underboob",
        "sideboob", "cameltoe", "skindentation", "ass visible through thighs", "thighs",
        // 메타 (docs/56 §5)
        "enmaided", "alternate costume", "cosplay", "official alternate costume", "heart"
    );
    static final List<Pattern> DROP_PATTERNS = List.of(
        Pattern.compile("background$"), Pattern.compile("^holding"), Pattern.compile("weapon"), Pattern.compile("sword"),
        Pattern.compile("blade"), Pattern.compile("scissors"),
        // 손·팔 '자세'만 — arm warmers·arm ribbon·hand tattoo 같은 복장·특징 태그는 남긴다(독립 검토 #15)
        Pattern.compile("^(hands?|arms?|fingers?) (up|down|on|in|to|at|behind|between|over|under|around|together|out|raised|extended)\\b"),
        Pattern.compile("^(own hands|v|peace sign|thumbs up|pointing)"),
        Pattern.compile("lighting$"), Pattern.compile("shadow$"), Pattern.compile("^from "),
        Pattern.compile("^looking"), Pattern.compile("pose$"), Pattern.compile("^signature"), Pattern.compile("^watermark"),
        Pattern.compile("text$"), Pattern.compile("^artist name"), Pattern.compile("^dated"), Pattern.compile("^border"),
        // 캐릭터·작품명 태그: "name \(series\)" 꼴 (복장 의미 태그 pom pom \(clothes\) 등은 화이트리스트)
        //   괄호 안 내용은 문자 제한 없이 본다(fate/grand order·k-on! 등). 태거 단계에서 character_threshold=1.0으로
        //   캐릭터 카테고리를 끄므로 이건 2차 방어선이다(독립 검토 #6).
        Pattern.compile("\\\\\\((?!(?:clothes|cosplay|object|hair|accessory|pattern|nail)\\\\\\))[^)]*\\\\\\)$")
    );
    /** 상한 — 태그가 많을수록 씬 일러 1인 씬 복제가 늘었다(docs/56 §3 관측, 인과 미검증). 스탠딩 리파인 기준 충분량. */
    static final int MAX_TAGS = 36;

    /** @param nodeOutput 노드 출력 문자열 "a, b, c\\(d\\)" — 여러 장이면 첫 장만 쓴다. */
    public static List<String> identityTags(String nodeOutput) {
        if (nodeOutput == null || nodeOutput.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : nodeOutput.split(",")) {
            String tag = raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
            if (tag.isEmpty() || DROP_EXACT.contains(tag)) continue;
            if (DROP_PATTERNS.stream().anyMatch(p -> p.matcher(tag).find())) continue;
            out.add(tag);
            if (out.size() >= MAX_TAGS) break;
        }
        return new ArrayList<>(out);
    }

    /** 얼굴 복원 힌트용 눈 색 태그 — "yellow eyes" 꼴의 첫 태그. */
    public static String eyeColor(List<String> tags) {
        return tags.stream().filter(t -> t.matches("[a-z]+ eyes")).findFirst().orElse(null);
    }
}
