package com.spring.aichat.service.ugc;

import com.spring.aichat.exception.BadRequestException;

/**
 * [D-19 / D-3.6 · docs/19_assets/decision_agenda.md D-19] UGC 캐릭터 텍스트 길이 상한 정본.
 *
 * <p><b>왜 필요한가</b> — {@code Character}의 name/tagline/role/tone은 VARCHAR(50/100/100/300)이고
 * 운영 프로필은 {@code ddl-auto=validate}다. 상한 검증이 어디에도 없어서, 유저 입력이나 Stage 0 LLM
 * 산출이 상한을 넘으면 <b>전 스테이지를 완주한 잡</b>이 마지막 바인딩({@code Character.createUgc} →
 * {@code save})에서 {@code value too long for type character varying} 로 죽고,
 * {@code UgcPipelineWorker.failAndRefund}가 <b>전액 환불</b>한다. 유저 순비용 0 · 잡은 terminal이라
 * 동시 1잡 게이트도 즉시 풀린다 → <b>순 0E 무한 GPU 드레인</b>이 성립한다(레지스터 D-3.6 (c)).
 *
 * <p><b>정책</b> — 유저 입력 경로는 <b>절삭이 아니라 400 거부</b>다(종원 확정 D-19). 유저가 쓴 문장 끝을
 * 조용히 자르면 편집 의도가 소실된다. 같은 저장소의 {@code UgcWorldService.updateWorld}가 이미
 * 400 거부인데 캐릭터 트랙만 비대칭이던 것을 맞춘다. 반대로 <b>LLM 산출·최종 바인딩은 절삭</b>이다
 * (이미 GPU를 다 쓴 시점이라 거부할 수 없다) — {@code ConceptStructuringService.normalizeShort} 참조.
 */
final class UgcTextLimits {

    private UgcTextLimits() {}

    /** Character.java:52 {@code @Column(nullable = false, length = 50) name} */
    static final int NAME_MAX = 50;
    /** Character.java:74 {@code @Column(name = "tagline", length = 100)} */
    static final int TAGLINE_MAX = 100;
    /** Character.java:91 {@code @Column(name = "role", length = 100)} */
    static final int ROLE_MAX = 100;
    /** Character.java:103 {@code @Column(name = "tone", length = 300)} */
    static final int TONE_MAX = 300;

    /**
     * 상한 초과 시 400. null·빈값은 통과(각 경로의 null=유지 / 빈값=삭제 시맨틱을 건드리지 않는다).
     * trim 후 길이로 판정한다 — 저장 시점에도 trim된 값이 들어가기 때문.
     */
    static void requireMax(String value, int max, String label) {
        if (value == null) return;
        if (value.trim().length() > max) {
            throw new BadRequestException("%s은(는) %d자 이하로 입력해 주세요.".formatted(label, max));
        }
    }

    /** 캐릭터 프로필 4종 일괄 검증 — 드래프트 편집·완성본 편집 두 경로가 공유한다. */
    static void requireCharacterTexts(String name, String tagline, String role, String tone) {
        requireMax(name, NAME_MAX, "이름");
        requireMax(tagline, TAGLINE_MAX, "한 줄 소개");
        requireMax(role, ROLE_MAX, "역할");
        requireMax(tone, TONE_MAX, "말투");
    }
}
