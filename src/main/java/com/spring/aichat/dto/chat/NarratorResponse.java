package com.spring.aichat.dto.chat;

import java.util.List;

public record NarratorResponse(
    List<EventOption> options,
    int userEnergy
) {
    public record EventOption(
        String type,        // NORMAL, AFFECTION, SECRET
        String summary,     // 버튼 제목
        String detail,      // 상세 내용 (선택 시 캐릭터에게 전송될 텍스트)
        int energyCost,     // [NEW] 에너지 비용 (2, 3, 4)
        boolean isSecret    // 시크릿 모드 전용 여부
    ) {}
}
