package com.spring.aichat.service.admin;

import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.dto.admin.QualityLogResponse;
import com.spring.aichat.dto.admin.QualitySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RLHF 품질 대시보드 (Phase 6). 최근 싫어요 응답 + dislikeReason 집계.
 * (dislikeReason 은 enum 이 아니라 String 이며 OTHER 를 포함한 6종 — 반드시 OTHER 포함해 집계)
 */
@Service
@RequiredArgsConstructor
public class AdminQualityService {

    private static final List<String> REASONS =
        List.of("OOC", "HALLUCINATION", "BORING", "REPETITIVE", "CONTEXT_MISMATCH", "OTHER");

    private final ChatLogMongoRepository chatLogMongoRepository;

    public Page<QualityLogResponse> recentDislikes(Pageable pageable) {
        return chatLogMongoRepository.findByRatingOrderByCreatedAtDesc("DISLIKE", pageable)
            .map(QualityLogResponse::from);
    }

    public QualitySummary summary() {
        long likes = chatLogMongoRepository.countByRating("LIKE");
        long dislikes = chatLogMongoRepository.countByRating("DISLIKE");
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (String r : REASONS) {
            byReason.put(r, chatLogMongoRepository.countByDislikeReason(r));
        }
        return new QualitySummary(likes, dislikes, byReason);
    }
}
