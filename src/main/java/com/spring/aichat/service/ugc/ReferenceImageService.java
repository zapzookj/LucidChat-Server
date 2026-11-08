package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.ugc.UgcReferenceImage;
import com.spring.aichat.domain.ugc.UgcReferenceImageRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.exception.RateLimitException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * [docs/56 §10] 그림 방식 — 레퍼런스 업로드·검사 (에너지 무과금).
 *
 * <p>흐름: 형식·용량 검사 → 디코드 → <b>서버 재인코딩</b>(EXIF·메타데이터 제거, 알파는 흰 배경 합성, 긴 변
 * {@value #MAX_SIDE}px 이하) → 비공개 경로 저장 → VLM 판정·판독 → 행 저장. 거절된 그림도 행을 남긴다
 * (남용 카운트·감사). 생성은 {@link #requireUsable}을 통과한 OK 그림만 쓸 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceImageService {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_SIDE = 1536;
    static final int MIN_SIDE = 256;
    /** 남용 방지 — 유저당 1시간 업로드 상한(판독 VLM 비용 상한). */
    static final int HOURLY_LIMIT = 20;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    private final UgcReferenceImageRepository repository;
    private final UserRepository userRepository;
    private final UgcAssetService assetService;
    private final ReferenceVisionService visionService;
    private final ObjectMapper objectMapper;

    /** Spring Boot 중첩 jar에서도 WebP 플러그인(TwelveMonkeys)이 잡히도록 명시 스캔. */
    @PostConstruct
    void registerImagePlugins() {
        ImageIO.scanForPlugins();
    }

    public UgcDtos.ReferenceView upload(String username, MultipartFile file) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
        if (repository.countByUserIdAndCreatedAtAfter(user.getId(), LocalDateTime.now().minusHours(1)) >= HOURLY_LIMIT) {
            throw new RateLimitException("그림을 너무 자주 올렸어요. 잠시 후 다시 시도해 주세요.");
        }
        byte[] png = normalize(readBytes(file));
        BufferedImage dims = decode(png); // 정규화본은 긴 변 1536px 이하 — 재디코드 안전
        String key = assetService.storeReference(user.getId(), png);

        ReferenceVisionService.ReferenceFeatures features;
        try {
            features = visionService.inspect(assetService.presignGet(key, PREVIEW_TTL));
        } catch (RuntimeException e) {
            // 판독 실패 — 행이 없어 정리 스케줄러가 못 찾는 고아 객체가 되므로 즉시 지운다
            try { assetService.delete(key); } catch (Exception ignored) { /* 정리 실패는 판독 오류를 가리지 않는다 */ }
            throw e;
        }
        UgcReferenceImage.Verdict verdict = features.verdictOrUnclear();
        UgcReferenceImage saved = repository.save(UgcReferenceImage.of(user.getId(), key, sha256(png),
            dims.getWidth(), dims.getHeight(), verdict, verdict == UgcReferenceImage.Verdict.OK ? write(features) : null));
        log.info("[UGC-REF] uploaded: user={}, ref={}, verdict={}", user.getId(), saved.getId(), verdict);

        if (verdict != UgcReferenceImage.Verdict.OK) {
            return new UgcDtos.ReferenceView(saved.getId(), verdict.name(), message(verdict), List.of(), null,
                assetService.presignGet(key, PREVIEW_TTL));
        }
        return new UgcDtos.ReferenceView(saved.getId(), verdict.name(), null,
            features.featuresKo() == null ? List.of() : features.featuresKo(),
            features.genderOrNull(), assetService.presignGet(key, PREVIEW_TTL));
    }

    /** 생성 시작 검증 — 본인 소유 + 판정 OK + 미정리. 타인 그림은 404 은닉. */
    public UgcReferenceImage requireUsable(Long userId, Long referenceId) {
        UgcReferenceImage r = repository.findByIdAndUserId(referenceId, userId)
            .orElseThrow(() -> new NotFoundException("그림을 찾을 수 없습니다. referenceId=" + referenceId));
        if (!r.usable()) throw new BadRequestException("이 그림으로는 소환할 수 없어요. 다른 그림을 올려 주세요.");
        // 보관 정리 직전 그림은 생성 도중 지워질 수 있다 — 정리 하루 전부터 새 생성에 쓰지 않는다
        if (r.getCreatedAt().isBefore(LocalDateTime.now().minusDays(ORPHAN_RETENTION_DAYS - 1))) {
            throw new BadRequestException("오래전에 올린 그림이에요. 그림을 다시 올려 주세요.");
        }
        return r;
    }

    public ReferenceVisionService.ReferenceFeatures featuresOf(UgcReferenceImage r) {
        try {
            return objectMapper.readValue(r.getFeaturesJson(), ReferenceVisionService.ReferenceFeatures.class);
        } catch (Exception e) {
            throw new IllegalStateException("레퍼런스 판독 파싱 실패: ref=" + r.getId(), e);
        }
    }

    /**
     * 보관 정책 정리 — 캐릭터로 이어지지 않은 그림을 {@value #ORPHAN_RETENTION_DAYS}일 뒤 스토리지에서 지운다
     * (행은 감사용으로 남기고 deleted_at 표기). 진행 중 잡이 쓰는 그림·완성 캐릭터의 그림은 제외.
     *
     * @return 정리한 건수
     */
    public int cleanupOrphans() {
        List<UgcReferenceImage> orphans = repository.findOrphansBefore(
            LocalDateTime.now().minusDays(ORPHAN_RETENTION_DAYS),
            List.of(com.spring.aichat.domain.ugc.CreationJobStatus.READY,
                com.spring.aichat.domain.ugc.CreationJobStatus.FAILED,
                com.spring.aichat.domain.ugc.CreationJobStatus.EXPIRED));
        int done = 0;
        for (UgcReferenceImage r : orphans) {
            try {
                assetService.delete(r.getStorageKey());
                r.markDeleted();
                repository.save(r);
                done++;
            } catch (Exception e) {
                log.warn("[UGC-REF] 정리 실패: ref={}, {}", r.getId(), e.getMessage());
            }
        }
        return done;
    }

    static final int ORPHAN_RETENTION_DAYS = 7;

    static String message(UgcReferenceImage.Verdict verdict) {
        return switch (verdict) {
            case UNCLEAR -> "캐릭터를 또렷하게 찾지 못했어요. 얼굴과 옷차림이 잘 보이는 그림으로 바꿔주세요.";
            case MULTIPLE -> "그림에 여러 명이 있어요. 소환할 캐릭터 한 명만 나온 그림으로 바꿔주세요.";
            case POLICY -> "이 그림은 운영 정책상 쓸 수 없어요. 다른 그림으로 다시 시도해주세요.";
            case OK -> null;
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("그림 파일이 비어 있어요.");
        if (file.getSize() > MAX_BYTES) throw new BadRequestException("이 파일은 올릴 수 없어요. 10MB 이하로 올려주세요.");
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new BadRequestException("그림을 읽지 못했어요. 다시 시도해주세요.");
        }
    }

    /** PNG·JPG·WebP만 허용(매직 바이트) → RGB 재인코딩 PNG. 메타데이터는 재인코딩으로 전부 떨어진다. */
    static byte[] normalize(byte[] raw) {
        if (!isPng(raw) && !isJpeg(raw) && !isWebp(raw)) {
            throw new BadRequestException("이 파일은 올릴 수 없어요. PNG·JPG·WebP 파일만 올려주세요.");
        }
        BufferedImage src = decodeBounded(raw);
        if (Math.min(src.getWidth(), src.getHeight()) < MIN_SIDE) {
            throw new BadRequestException("그림이 너무 작아요. 짧은 변이 " + MIN_SIDE + "px 이상인 그림을 올려주세요.");
        }
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE); // 투명 배경 그림은 흰 배경으로 — 태거·편집 모델 모두 흰 배경 패딩 규약
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("레퍼런스 재인코딩 실패", e);
        }
    }

    /** 헤더만 읽은 원본 크기 상한 — 10MB PNG도 2만×2만을 선언할 수 있다(전체 디코드 시 힙 1GB+). */
    static final int MAX_SOURCE_SIDE = 8000;
    static final long MAX_SOURCE_PIXELS = 40_000_000L;

    /**
     * 디코딩 폭탄 방어 — {@link javax.imageio.ImageReader}로 헤더의 가로·세로만 먼저 읽어 상한을 넘으면 거절하고,
     * 큰 그림은 서브샘플링으로 목표 크기 근처까지 줄여 디코드한다(메모리 상한 ≈ 2×{@value #MAX_SIDE}² 픽셀).
     */
    static BufferedImage decodeBounded(byte[] bytes) {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) throw new BadRequestException("그림을 읽지 못했어요. 다른 파일로 다시 시도해주세요.");
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SOURCE_SIDE || h > MAX_SOURCE_SIDE || (long) w * h > MAX_SOURCE_PIXELS) {
                    throw new BadRequestException("그림이 너무 커요. 가로·세로 " + MAX_SOURCE_SIDE + "px 이하로 올려주세요.");
                }
                int factor = Math.max(1, Math.max(w, h) / (MAX_SIDE * 2));
                javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                if (factor > 1) param.setSourceSubsampling(factor, factor, 0, 0);
                BufferedImage img = reader.read(0, param);
                if (img == null) throw new BadRequestException("그림을 읽지 못했어요. 다른 파일로 다시 시도해주세요.");
                return img;
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("그림을 읽지 못했어요. 다른 파일로 다시 시도해주세요.");
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new BadRequestException("그림을 읽지 못했어요. 다른 파일로 다시 시도해주세요.");
            return img;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("그림을 읽지 못했어요. 다른 파일로 다시 시도해주세요.");
        }
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF;
    }

    static boolean isWebp(byte[] b) {
        return b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
