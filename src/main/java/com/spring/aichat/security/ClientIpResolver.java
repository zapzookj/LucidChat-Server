package com.spring.aichat.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * [블록 A 게스트 브라우징] 비인증 요청의 클라이언트 IP 해석 유틸.
 *
 * <p>X-Forwarded-For의 <b>최우측</b> 값을 취한다 — 프로드는 ALB(신뢰 프록시 1홉)가
 * 실제 접속 IP를 리스트 끝에 append하므로, 클라이언트가 임의 주입 가능한 최좌측 값
 * (docs/13 B-11의 우회 벡터)과 달리 위조할 수 없다. XFF 부재(로컬 직결) 시 remoteAddr.
 *
 * <p>기존 {@code AuthController.extractClientIp}(최좌측 사용)는 의도적으로 손대지 않는다 —
 * docs/13 B-11 수정은 버그 픽스 세션 몫. 신규 게스트 경로만 본 유틸을 쓴다.
 *
 * <p><b>⚠ 인프라 전제(적대적 리뷰 P2)</b>: 최우측 신뢰는 "모든 트래픽이 ALB를 경유한다"는
 * 전제에서만 성립한다. ECS 태스크 SG 인그레스가 ALB SG 외로 열려 있으면 직접 접속 +
 * 매 요청 다른 XFF로 레이트리밋 버킷을 분산시킬 수 있다 — 코드가 아닌 SG로 막는 항목이며,
 * docs/06 §7 보안 체크리스트(태스크 SG=ALB 전용 인그레스 확인)에 합류시킬 것.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String candidate = parts[parts.length - 1].trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return request.getRemoteAddr();
    }
}
