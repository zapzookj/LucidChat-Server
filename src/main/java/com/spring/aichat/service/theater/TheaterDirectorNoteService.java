package com.spring.aichat.service.theater;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.theater.TheaterDirectorNote;
import com.spring.aichat.domain.theater.TheaterDirectorNoteRepository;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.theater.TheaterStateRepository;
import com.spring.aichat.dto.theater.TheaterResponses.DirectorCommandResult;
import com.spring.aichat.dto.theater.TheaterResponses.DirectorNoteView;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.ContentModerationService;
import com.spring.aichat.service.ContentModerationService.ModerationVerdict;
import com.spring.aichat.service.theater.TheaterCommandClassifier.ClassificationResult;
import com.spring.aichat.service.theater.TheaterCommandClassifier.CommandVerdict;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [Phase 5.5-Theater] 감독 노트 서비스
 *
 * 두 가지 역할:
 *  1. 일반 메모 (MANUAL 노트, AUTO 노트) CRUD
 *  2. [Phase 5.5 UX Polish · R3] 감독 명령어 발동
 *     - 유저 입력을 검증 파이프라인(인젝션 가드 → 콘텐츠 모더레이션 → 분류기)
 *       통과 시 Redis 활성 큐에 등록 + DB에 명령어 노트로 저장
 *     - 거부된 명령어도 기록 보관 (유저 학습 자료)
 *     - 다음 배치 생성 시 BatchGenerator가 큐에서 consume하여 프롬프트에 흡수
 *
 * 활성 큐 정책:
 *  - 한 배치당 1개의 활성 명령어만 (max=1)
 *  - 새 명령어 발동 시 기존 활성 명령어 덮어쓰기 (controller에서 confirm 받음)
 *  - 1배치 일회성 — consume되면 큐는 비워짐 (TTL 별도 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterDirectorNoteService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterDirectorNoteRepository directorNoteRepository;
    private final TheaterStateRepository theaterStateRepository;

    // [R3] 명령어 발동 검증 의존성
    private final PromptInjectionGuard injectionGuard;
    private final ContentModerationService contentModerationService;
    private final TheaterCommandClassifier commandClassifier;
    private final TheaterBatchCacheService batchCache;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  명령어 입력 정책
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 명령어 최대 길이 — UX 적정 + 안전 */
    private static final int COMMAND_MAX_LENGTH = 300;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  일반 메모 CRUD
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public List<DirectorNoteView> listNotes(Long roomId, String username) {
        getOwnedRoom(roomId, username);
        return directorNoteRepository.findByRoom_IdOrderByCreatedAtAsc(roomId).stream()
            .map(this::toView).toList();
    }

    @Transactional
    public DirectorNoteView createNote(Long roomId, String username, String content) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterDirectorNote note = TheaterDirectorNote.manual(room, content, null, null);
        directorNoteRepository.save(note);
        log.info("🎭 [NOTE] created | roomId={}", roomId);
        return toView(note);
    }

    @Transactional
    public DirectorNoteView updateNote(Long roomId, String username, Long noteId, String content) {
        getOwnedRoom(roomId, username);
        TheaterDirectorNote note = directorNoteRepository.findById(noteId)
            .orElseThrow(() -> new NotFoundException("노트를 찾을 수 없습니다."));
        if (!"MANUAL".equals(note.getNoteType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "자동 생성된 노트는 수정할 수 없습니다.");
        }
        // [R3] 명령어로 발동된 노트는 수정 불가 (이미 사용된 기록은 영구 보존)
        if (note.getCommandType() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "감독 명령어는 수정할 수 없습니다.");
        }
        note.updateContent(content);
        return toView(note);
    }

    @Transactional
    public void deleteNote(Long roomId, String username, Long noteId) {
        getOwnedRoom(roomId, username);
        TheaterDirectorNote note = directorNoteRepository.findById(noteId)
            .orElseThrow(() -> new NotFoundException("노트를 찾을 수 없습니다."));
        if (!"MANUAL".equals(note.getNoteType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "자동 생성된 노트는 삭제할 수 없습니다.");
        }
        // [R3] 명령어 노트(수락/거부 모두) 삭제 불가 — 감사 / 통계 / 유저 학습용 영구 보존
        if (note.getCommandType() != null || note.getValidationVerdict() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "감독 명령어 기록은 삭제할 수 없습니다.");
        }
        directorNoteRepository.delete(note);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R3] 감독 명령어 발동
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 감독 명령어 발동.
     *
     * 검증 파이프라인:
     *  1. 길이 / 빈 문자열 검사
     *  2. PromptInjectionGuard sanitize (구조 토큰 제거)
     *  3. ContentModerationService (시크릿 모드 OFF에서만)
     *  4. TheaterCommandClassifier (룰 기반 + LLM 하이브리드)
     *  5. 통과 시 Redis active 큐에 등록 + DB에 명령어 노트 저장
     *
     * 거부된 명령어도 DB에 기록 (유저 학습 + 통계 + 감사).
     *
     * @return DirectorCommandResult — accepted 여부 + 사유 + 생성된 노트
     */
    @Transactional
    public DirectorCommandResult triggerCommand(Long roomId, String username, String rawText) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 상태를 찾을 수 없습니다."));

        // 1. 기본 길이 검사
        if (rawText == null || rawText.isBlank()) {
            throw new BadRequestException("명령어를 입력해 주세요.");
        }
        String trimmed = rawText.trim();
        if (trimmed.length() > COMMAND_MAX_LENGTH) {
            throw new BadRequestException("명령어는 " + COMMAND_MAX_LENGTH + "자 이내로 입력해 주세요.");
        }

        // 2. PromptInjectionGuard sanitize — 구조 토큰 제거
        String sanitized = injectionGuard.sanitizePersona(trimmed);
        // sanitizePersona는 인젝션 패턴을 [REDACTED]로 치환. REDACTED가 포함되면 거부.
        if (sanitized.contains("[REDACTED]")) {
            return saveAndReturn(room, state, trimmed,
                CommandVerdict.REJECTED_INJECTION,
                "허용되지 않는 명령 패턴이 감지되었습니다.");
        }

        // 3. 콘텐츠 모더레이션 — 시크릿 모드 OFF일 때만 (시크릿이면 PASS)
        boolean secretOn = room.isSecretModeActive();
        ModerationVerdict moderation = contentModerationService.moderate(sanitized, secretOn);
        if (!moderation.passed()) {
            log.info("🎬 [CMD] moderation blocked | roomId={} | category={} | step={}",
                roomId, moderation.category(), moderation.blockedAtStep());
            String message = moderation.userMessage() != null
                ? moderation.userMessage()
                : "이 명령어는 사용할 수 없습니다. 시크릿 모드에서만 가능한 표현이 포함되어 있습니다.";
            return saveAndReturn(room, state, trimmed, null, message);
        }

        // 4. 명령어 분류기 — 룰 + LLM
        ClassificationResult cls = commandClassifier.classify(sanitized, roomId);

        if (!cls.isAllowed()) {
            log.info("🎬 [CMD] rejected | roomId={} | verdict={} | text='{}'",
                roomId, cls.verdict(), truncate(trimmed));
            return saveAndReturn(room, state, trimmed, cls.verdict(),
                cls.verdict().userMessage());
        }

        // 5. 통과 — DB 저장 + Redis 활성 큐 등록
        TheaterDirectorNote note = TheaterDirectorNote.command(
            room, sanitized,
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            cls.verdict().toCommandType(),
            cls.verdict().name()
        );
        directorNoteRepository.save(note);

        // Redis: active 큐에 등록 (max 1 — 기존 큐 덮어쓰기)
        batchCache.setActiveDirectorCommand(roomId, sanitized, note.getId());

        log.info("🎬 [CMD] accepted | roomId={} | type={} | text='{}'",
            roomId, cls.verdict().toCommandType(), truncate(trimmed));

        return new DirectorCommandResult(
            true,
            cls.verdict().name(),
            cls.verdict().userMessage(),
            toView(note)
        );
    }

    /** 거부 / 차단 시 DB에 기록만 보관하는 헬퍼 */
    private DirectorCommandResult saveAndReturn(
        ChatRoom room, TheaterState state, String content,
        CommandVerdict verdict, String userMessage
    ) {
        String verdictName = verdict != null ? verdict.name() : "REJECTED_CONTENT";
        TheaterDirectorNote note = TheaterDirectorNote.rejectedCommand(
            room, content,
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            verdictName
        );
        directorNoteRepository.save(note);
        return new DirectorCommandResult(false, verdictName, userMessage, toView(note));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  변환 / 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private DirectorNoteView toView(TheaterDirectorNote n) {
        return new DirectorNoteView(
            n.getId(), n.getNoteType(), n.getContent(),
            n.getActNumber(), n.getChapterNumber(),
            n.getRelatedHeroineId(), null,
            n.getRelatedIllustrationUrl(),
            n.getCommandType(),
            n.getValidationVerdict(),
            n.getWasUsed(),
            n.getUsedInBatchId(),
            n.getCreatedAt()
        );
    }

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        return room;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}