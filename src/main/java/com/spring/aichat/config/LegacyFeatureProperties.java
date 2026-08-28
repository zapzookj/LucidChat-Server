package com.spring.aichat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * [블록 D · docs/14 §C#6 · §G] 레거시 미연시 문법 게이트 플래그.
 *
 * <p>원칙은 <b>"코드 보존, 진입만 차단"</b>이다 — 삭제가 아니라 노브를 쓰는 이유는
 * 되살릴 여지를 남기고, 되살릴 때 데이터 정합이 이미 맞아 있어야 하기 때문이다.
 * (§G의 🔴삭제 목록은 이 플래그가 아니라 실제 코드 제거로 처리한다.)
 *
 * <p><b>게이트는 반드시 서버측이다.</b> 프론트 진입점만 지우면 API가 소유권 검사만으로 열린 채
 * 남는다 — {@code /users/beta-activate}가 정확히 그 실수였다(docs/14_assets §5).
 *
 * <p>기본값은 전부 {@code false}(= 게이트 오프 상태). 환경변수로 켤 수 있다.
 *
 * @see com.spring.aichat.service.AchievementService
 * @see com.spring.aichat.controller.EndingController
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "legacy")
public class LegacyFeatureProperties {

    private Ending ending = new Ending();
    private Achievement achievement = new Achievement();
    private Illustration illustration = new Illustration();
    private Unlock unlock = new Unlock();

    @Getter @Setter
    public static class Ending {
        /**
         * 자유(SANDBOX)·스토리(V2 STORY) 엔딩 활성 여부.
         * 극장 엔딩은 §C#6에서 '유지' 확정이라 이 플래그의 사정권 밖이다
         * ({@code TheaterFinalityController}는 검사하지 않는다).
         */
        private boolean dialogueEnabled = false;
    }

    @Getter @Setter
    public static class Achievement {
        /**
         * 업적 지급·갤러리·해금 모달 활성 여부.
         * 이스터에그 <i>연출</i>은 유지된다 — {@code EasterEggEvent}는 계속 내려가고
         * {@code achievement} 필드만 null이 된다.
         */
        private boolean enabled = false;
    }

    @Getter @Setter
    public static class Illustration {
        /** §G-6 레거시 캐릭터 일러(ModelsLab CG) 트랙. 씬 일러로 일원화·동결. */
        private boolean legacyCgEnabled = false;

        /**
         * [docs/19 안건 10 = (c) 종원 확정] 레거시 CG 트랙의 <b>자동 생성</b> 경로.
         *
         * <p>{@code legacyCgEnabled}는 유저가 버튼을 눌러 10E를 내는 수동 경로
         * ({@code IllustrationController:115})만 막았고, 서비스 계층
         * {@code IllustrationService.generateAutoIllustration}은 무게이트로 남아 있었다.
         * 그 결과 극장 자동 노트 3경로(AUTO_MOMENT / BRANCH_TAKEN / CHAPTER_END)가
         * <b>유저 에너지 차감 0으로 ModelsLab 외부 과금을 계속 발생</b>시켰다(지표에도 안 잡힌다).
         *
         * <p>수동 경로와 분리한 이유: 서비스 계층을 {@code legacyCgEnabled}로 곧장 막으면
         * 극장의 유일한 캐릭터 이미지 산출물(다이어리 카드)이 사라져 docs/14 §C#6 '극장 무변경'과
         * 정면 충돌한다. 전용 노브로 두면 <b>되돌릴 수 있고 데이터 정합도 유지</b>된다
         * (판정은 '소멸'이 아니라 '게이트차단' — docs/19 §B).
         *
         * <p>자유 대화 엔딩 자동 CG({@code ChatStreamService})도 같은 메서드를 타지만
         * 그쪽은 이미 {@code legacy.ending.dialogue-enabled}로 상위에서 차단돼 있다.
         */
        private boolean theaterAutoCgEnabled = false;
    }

    @Getter @Setter
    public static class Unlock {
        /**
         * §G-5 복장·장소 '관계 단계별 해금'.
         * 복장/장소 값 자체는 살아있는 연출 변수다 — 죽이는 건 LOCK 규칙뿐(impl_spec §5).
         */
        private boolean relationGated = false;
    }
}
