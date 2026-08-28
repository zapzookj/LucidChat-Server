package com.spring.aichat.dto.theater;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOption;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOptions;

import java.util.List;

/**
 * [버그픽스 B-4.a/b/c/d/e/f · docs/17_assets/defect_register.md]
 * 서버가 발급한 "분기 오퍼" 원본 스냅샷 (Redis 캐시 페이로드).
 *
 * <p><b>왜 필요한가</b> — 기존 확정 경로(applyBranchChoice)는 클라이언트가 보낸
 * {@code optionsSnapshot}·{@code level}·{@code energyCost}·{@code unlocked}·{@code heroineId}를
 * 그대로 믿었다. 서버는 옵션을 생성할 때만 정답을 계산하고 <b>어디에도 저장하지 않았기 때문에</b>
 * 확정 시점에 대조할 원본이 없었다. 이 record가 그 "서버 원본"이며, 확정은 오직 여기서만
 * 값을 읽는다(요청 본문은 전부 무시).
 *
 * <p><b>키 설계</b> — 방당 활성 오퍼 1개({@code theater:branch:offer:{roomId}}).
 * 토큰별 키로 두면 세이브 로드({@code TheaterSaveLoadService} → {@code purgeRoom})에서
 * 고아 오퍼가 남아 <b>롤백된 시점의 분기가 재적용</b>될 수 있다. 방당 단일 키라야
 * purgeRoom이 한 줄로 정리한다.
 *
 * @param token             서버 발급 1회용 토큰(UUID). 확정 요청이 되돌려 보내야 하는 값.
 * @param level             서버 확정 분기 레벨. 클라 {@code level} 필드는 이제 무시된다(B-4.b/e).
 * @param contextSummary    서버 확정 분기 컨텍스트. 씬 분기는 캐시된 배치의 {@code branchSignal.context()},
 *                          LOCATION은 null. <b>클라이언트 자유 입력을 절대 담지 않는다</b>(B-4.d).
 * @param contextNarration  유저 노출용 상황 묘사. 멱등 재발급 시 같은 문구를 돌려주기 위해 보관.
 * @param sourceBatchId     이 분기를 실은 배치 id. 씬 분기의 멱등키. LOCATION은 -1.
 * @param options           서버가 산출한 옵션 원본(unlocked·energyCost·heroineId 전부 서버 값).
 * @param issuedAtEpochMs   발급 시각(진단용).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BranchOffer(
    String token,
    String level,
    String contextSummary,
    String contextNarration,
    int sourceBatchId,
    int actNumber,
    int chapterNumber,
    long sceneSequence,
    List<BranchOption> options,
    long issuedAtEpochMs
) {

    /** 오퍼 원본을 그대로 응답 DTO로 환원 — 멱등 재발급 시 LLM 재호출 없이 같은 응답을 돌려준다. */
    public BranchOptions toResponse() {
        return new BranchOptions(
            level, contextNarration, options,
            actNumber, chapterNumber, sceneSequence, token
        );
    }

    /**
     * 멱등키 일치 여부.
     * 씬 분기는 (level, sourceBatchId), LOCATION은 (level, act, chapter)로 판정한다.
     * 일치하면 LLM 재호출 없이 같은 오퍼를 재사용 → 새로고침·prefetch 중복·모달 재진입이 무료가 되고
     * "만료" 개념이 유저에게 드러나지 않는다(docs/19 안건 14 (c)의 실현 방식).
     */
    public boolean matches(String otherLevel, int otherSourceBatchId, int act, int chapter) {
        return level != null
            && level.equals(otherLevel)
            && sourceBatchId == otherSourceBatchId
            && actNumber == act
            && chapterNumber == chapter;
    }
}
