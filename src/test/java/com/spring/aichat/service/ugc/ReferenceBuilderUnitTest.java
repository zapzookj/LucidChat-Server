package com.spring.aichat.service.ugc;

import com.spring.aichat.dto.ugc.FaceDetailTags;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** [docs/56 §10] 그림 방식 순수 로직 — 조정값·프롬프트·태그 필터·업로드 재인코딩. */
class ReferenceBuilderUnitTest {

    // ── 조정값 정규화 · 프롬프트 ──

    @Test void sanitizeDropsUnknownValuesAndReturnsNullWhenNothingChanges() {
        assertThat(ReferencePrompts.sanitize(null)).isNull();
        assertThat(ReferencePrompts.sanitize(new UgcDtos.ReferenceAdjustments("keep", null, "keep", "keep", " "))).isNull();
        // 허용 밖 값(구버전 프런트·조작)은 400 대신 "그대로"로 떨어진다
        assertThat(ReferencePrompts.sanitize(new UgcDtos.ReferenceAdjustments("bikini", null, "tall", "huge", null))).isNull();
        // custom인데 텍스트가 비면 복장 변경 없음
        assertThat(ReferencePrompts.sanitize(new UgcDtos.ReferenceAdjustments("custom", "  ", "keep", "keep", null))).isNull();

        var a = ReferencePrompts.sanitize(new UgcDtos.ReferenceAdjustments("custom", "검은 후드티".repeat(10), "curvy", "keep", "x".repeat(80)));
        assertThat(a.outfitText()).hasSize(40);
        assertThat(a.extra()).hasSize(60);
        assertThat(a.body()).isEqualTo("curvy");
        assertThat(a.bust()).isEqualTo("keep");
    }

    @Test void goldenPromptPreservesOutfitUnlessChangedAndQuotesUserText() {
        String keep = ReferencePrompts.golden(null);
        assertThat(keep).contains("the outfit and every clothing detail").doesNotContain("Apply these changes")
            .contains("cowboy shot").contains("both hands empty");

        var maid = new UgcDtos.ReferenceAdjustments("maid", null, "keep", "large", null);
        String changed = ReferencePrompts.golden(maid);
        assertThat(changed).doesNotContain("the outfit and every clothing detail")
            .contains("maid dress").contains("Replace the original outfit entirely").contains("a larger bust");

        var custom = new UgcDtos.ReferenceAdjustments("custom", "흰 \"원피스\"\n줄바꿈", "keep", "keep", null);
        assertThat(ReferencePrompts.golden(custom)).contains("\"흰 '원피스' 줄바꿈\"");
        assertThat(ReferencePrompts.userText(custom)).isEqualTo("흰 \"원피스\"\n줄바꿈");
    }

    @Test void fixPromptKeepsCompositionAndNeedsAChange() {
        assertThat(ReferencePrompts.hasChanges(null)).isFalse();
        var a = new UgcDtos.ReferenceAdjustments("suit", null, "slim", "keep", null);
        assertThat(ReferencePrompts.hasChanges(a)).isTrue();
        assertThat(ReferencePrompts.fix(a)).startsWith("Edit only the following").contains("business suit")
            .contains("slender build").contains("framing, pose, lighting and background unchanged");
        assertThat(ReferencePrompts.koreanSummary(a)).isEqualTo("복장: 정장, 슬림한 체형");
    }

    // ── 태그 필터 ──

    @Test void identityTagsDropPoseBodyEmphasisMetaAndCharacterNames() {
        String node = "nahida \\(genshin impact\\), 1girl, solo, long hair, blonde hair, yellow eyes, thigh gap, "
            + "covered navel, collarbone, enmaided, alternate costume, skirt hold, looking at viewer, holding sword, "
            + "pom pom \\(clothes\\), medical eyepatch, white background, cowboy shot, hand up, maid headdress";
        List<String> tags = IdentityTagFilter.identityTags(node);
        assertThat(tags).containsExactly("long hair", "blonde hair", "yellow eyes", "pom pom \\(clothes\\)",
            "medical eyepatch", "maid headdress");
        assertThat(IdentityTagFilter.eyeColor(tags)).isEqualTo("yellow eyes");
        assertThat(IdentityTagFilter.identityTags(null)).isEmpty();
    }

    @Test void identityTagsAreCappedAndDeduplicated() {
        StringBuilder sb = new StringBuilder("blue hair, Blue Hair, ");
        for (int i = 0; i < 60; i++) sb.append("tag").append(i).append(", ");
        List<String> tags = IdentityTagFilter.identityTags(sb.toString());
        assertThat(tags).hasSize(IdentityTagFilter.MAX_TAGS);
        assertThat(tags.get(0)).isEqualTo("blue hair");
        assertThat(tags.get(1)).isEqualTo("tag0");
    }

    @Test void withTagsSwapsAppearanceAndEyeButKeepsEverythingElse() {
        var profile = new StructuredConcept.CharacterProfile("루미", null, 21, null, null, null, "외형", "복장",
            null, null, null, null, "안녕", null, null, null, null, null, null);
        var c = new StructuredConcept(List.of("from vlm"), List.of("calm"), List.of(), List.of(), "muted teal",
            profile, null, null, null, null, new FaceDetailTags("red eyes", "smug"));
        var swapped = ReferenceGoldenService.withTags(c, List.of("blonde hair", "yellow eyes"));
        assertThat(swapped.appearanceTags()).containsExactly("blonde hair", "yellow eyes");
        assertThat(swapped.faceDetailTags()).isEqualTo(new FaceDetailTags("yellow eyes", "smug"));
        assertThat(swapped.character()).isSameAs(profile);
        assertThat(swapped.bgColor()).isEqualTo("muted teal");
        // 태거 실패(빈 태그) — Stage0 판독 태그 유지
        assertThat(ReferenceGoldenService.withTags(c, List.of())).isSameAs(c);
    }

    // ── 업로드 재인코딩 ──

    @Test void normalizeRejectsNonImagesAndTinyImages() throws Exception {
        assertThatThrownBy(() -> ReferenceImageService.normalize("GIF89a....".getBytes()))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("PNG·JPG·WebP");
        assertThatThrownBy(() -> ReferenceImageService.normalize(png(100, 120, true)))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("너무 작아요");
    }

    @Test void normalizeDownscalesFlattensAlphaAndOutputsPng() throws Exception {
        byte[] out = ReferenceImageService.normalize(png(3000, 1500, true));
        assertThat(ReferenceImageService.isPng(out)).isTrue();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(img.getWidth()).isEqualTo(ReferenceImageService.MAX_SIDE);
        assertThat(img.getHeight()).isEqualTo(768);
        assertThat(img.getColorModel().hasAlpha()).isFalse();
        assertThat(img.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFFFFFF); // 투명 → 흰 배경
    }

    @Test void hugeDeclaredImagesAreRejectedBeforeFullDecode() throws Exception {
        assertThatThrownBy(() -> ReferenceImageService.normalize(png(9000, 300, false)))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("너무 커요");
        // 상한 안쪽의 큰 그림은 서브샘플링 후 1536 이하로 정규화된다
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(ReferenceImageService.normalize(png(7000, 3500, false))));
        assertThat(img.getWidth()).isEqualTo(ReferenceImageService.MAX_SIDE);
    }

    @Test void partialAdjustmentPayloadsDoNotThrow() {
        assertThat(ReferencePrompts.sanitize(new UgcDtos.ReferenceAdjustments(null, null, null, null, null))).isNull();
        var a = ReferencePrompts.sanitize(new UgcDtos.ReferenceAdjustments(null, null, "slim", null, null));
        assertThat(a.outfit()).isEqualTo("keep");
        assertThat(a.bust()).isEqualTo("keep");
        assertThat(ReferencePrompts.golden(new UgcDtos.ReferenceAdjustments(null, null, null, "large", null))).contains("a larger bust");
    }

    @Test void clothingOnArmsAndHandsSurvivesButPosesAndSeriesNamesDoNot() {
        List<String> tags = IdentityTagFilter.identityTags(
            "arm warmers, hand tattoo, arm ribbon, hand on own chest, arms behind back, hands up, v, "
            + "mash kyrielight \\(fate/grand order\\), yui \\(k-on!\\), pom pom \\(clothes\\)");
        assertThat(tags).containsExactly("arm warmers", "hand tattoo", "arm ribbon", "pom pom \\(clothes\\)");
    }

    @Test void webpMagicIsRecognized() {
        byte[] header = "RIFF\0\0\0\0WEBPVP8 ".getBytes();
        assertThat(ReferenceImageService.isWebp(header)).isTrue();
        assertThat(ReferenceImageService.isJpeg(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isTrue();
    }

    private static byte[] png(int w, int h, boolean transparent) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (!transparent) for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, 0xFF336699);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        }
    }
}
