package com.spring.aichat.service.ugc;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterVisibility;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [E-5.3.b] 월드 내용 변경으로 인한 <b>공개 캐릭터 재심사 회귀</b>의 단일 출처.
 *
 * <p>월드 텍스트·장소가 바뀌면 그 월드에 연결된 {@code PUBLIC} 캐릭터는 미검수 내용을
 * 프롬프트에 싣게 되므로 {@code PENDING_PUBLIC}으로 되돌려 어드민 큐에 재편입시킨다.
 * 종전엔 월드의 {@code reviewStatus}만 NONE으로 떨어지고 캐릭터는 공개된 채 남아 리셋이 실효가 없었다.
 *
 * <p><b>왜 별도 빈인가</b> — 회귀 트리거가 두 곳이다: 텍스트 수정은 {@code UgcWorldService}(동기),
 * 장소 추가는 {@code UgcWorldPipelineWorker}(비동기 완성 시점). 서비스에 private으로 두면 워커가 못 쓰고,
 * 워커에 두면 서비스가 워커를 부르는 기존 방향과 엇갈린다.
 *
 * <p><b>★ 적대적 검토가 잡은 것</b> — 이 회귀는 <b>라이브 콘텐츠를 내리는 파괴적 부작용</b>이다.
 * 캐릭터가 {@code PENDING_PUBLIC}이 되면 {@code Character.isAccessibleBy}가 소유자 외에 false가 되어
 * ① 로비·탐색에서 사라지고 ② 타 유저의 진행 중 대화가 다음 전송에서 {@code CHARACTER_UNAVAILABLE}로 끊긴다.
 * 그런데 종전 구현은 서버 로그 한 줄이 전부였다 — 같은 결과를 만드는 관리자 경로
 * ({@code AdminUgcReviewService.unpublish})는 감사 로그 + 소유자 알림을 둘 다 남기는데 비대칭이었다.
 * 그래서 여기서 <b>알림과 감사 로그를 함께</b> 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcReviewRevertService {

    /** [E-5.3.b] 시스템 회귀의 감사 액션 — 관리자 강제 철회(UGC_UNPUBLISH)와 구분한다. */
    public static final String AUDIT_ACTION = "UGC_REREVIEW_REVERT";

    private final CharacterRepository characterRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    /**
     * 이 월드에 연결된 PUBLIC 캐릭터를 재심사 대기로 되돌린다.
     * 호출자의 트랜잭션에 참여한다(별도 TX를 열지 않는다) — 월드 변경과 회귀는 원자적이어야 한다.
     *
     * @param reason 감사 로그·알림 본문에 실릴 사유(예: "세계관 설정 수정")
     * @return 실제로 되돌린 캐릭터 수
     */
    public int revertLinkedPublicCharacters(Long worldId, String reason) {
        List<Character> published =
            characterRepository.findByUgcWorldIdAndVisibility(worldId, CharacterVisibility.PUBLIC);
        int reverted = 0;
        for (Character c : published) {
            if (!c.revertToReviewForWorldChange()) continue;
            characterRepository.save(c);
            reverted++;
            notifyOwner(c, reason);
            auditLogService.record("SYSTEM", AUDIT_ACTION, "CHARACTER", String.valueOf(c.getId()),
                "세계관 변경(" + reason + ")으로 공개 캐릭터를 재심사 대기로 전환 — worldId=" + worldId);
        }
        if (reverted > 0) {
            log.info("[UGC-WORLD] 재심사 회귀: worldId={}, 사유={}, 캐릭터={}건", worldId, reason, reverted);
        }
        return reverted;
    }

    /** 이 월드에 연결된 공개 캐릭터 수 — FE 확인창이 '무엇이 내려가는지' 고지하는 데 쓴다. */
    public long countLinkedPublicCharacters(Long worldId) {
        return characterRepository.countByUgcWorldIdAndVisibility(worldId, CharacterVisibility.PUBLIC);
    }

    /**
     * 소유자 알림 — 관리자 철회({@code AdminUgcReviewService.notifyOwner})와 같은 형식.
     * 알림이 없으면 창작자는 "왜 갑자기 내 캐릭터가 안 보이지"를 스스로 알아내야 한다.
     */
    private void notifyOwner(Character c, String reason) {
        if (c.getOwnerUserId() == null) return;
        notificationService.notify(
            c.getOwnerUserId(),
            "UGC_REVIEW_RESULT",
            "세계관 수정으로 재심사에 들어갔어요",
            "'" + c.getName() + "'이(가) " + reason + "으로 재심사 대기 상태가 됐어요. "
                + "심사를 통과하면 다시 공개됩니다.",
            "UGC_CHARACTER",
            String.valueOf(c.getId()));
    }
}
