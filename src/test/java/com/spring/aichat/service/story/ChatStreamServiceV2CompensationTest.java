package com.spring.aichat.service.story;

import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.service.cache.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * V2 보상(rollback) 경로 단위 테스트.
 *
 * <p>회귀 대상 결함 2건:
 * <ol>
 *   <li>compensateEnergy가 {@code chatRoomRepository.findById(userId)}로 ChatRoom을 조회
 *       — ID 우연 일치 시 남의 방 유저에게 환불, 불일치 시 조용한 no-op.
 *       → userRepository로 유저 직접 조회해야 한다.</li>
 *   <li>compensateFullRollback(LLM 실패/파싱 실패 경로)이 로그 삭제+캐시 무효화만 하고
 *       TX-1에서 차감된 에너지를 환불하지 않음 → 에너지 소실.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatStreamServiceV2CompensationTest {

    private static final Long USER_ID = 7L;
    private static final String USERNAME = "tester";
    private static final int ENERGY_COST = 2;

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatLogMongoRepository chatLogRepository;
    @Mock private TransactionTemplate txTemplate;
    @Mock private RedisCacheService cacheService;

    @InjectMocks private ChatStreamServiceV2 service;

    private User user;
    /** [D-1.4] TX-1 차감이 돌려준 분할 — 보상은 총액이 아니라 이 값으로 되돌린다. */
    private EnergySplit charge;

    @BeforeEach
    void setUp() {
        // txTemplate.execute → 콜백 즉시 실행 (트랜잭션 인프라 없이 로직만 검증)
        when(txTemplate.execute(any())).thenAnswer(inv ->
            ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        // freeEnergy 기본 30 → TX-1 차감 시뮬레이션으로 28
        user = User.local(USERNAME, "pw", "nick", "tester@test.com");
        charge = user.consumeEnergy(ENERGY_COST);
        assertEquals(28, user.getEnergy());
        assertEquals(new EnergySplit(ENERGY_COST, 0), charge);
    }

    // ━━━━━━━━━━ [D-1.4] 분할 환불 — 유료분 정확 복원 ━━━━━━━━━━

    @Test
    @DisplayName("compensateEnergy: free=0/paid 차감분은 paid로 정확히 돌아온다 (free로 흡수돼 소각되지 않음)")
    void compensateEnergy_restoresPaidPortionExactly() {
        User paidUser = User.local("paid", "pw", "nick", "paid@test.com");
        paidUser.consumeEnergy(30);          // free 소진 → free=0
        paidUser.chargePaidEnergy(10);       // paid=10
        EnergySplit paidCharge = paidUser.consumeEnergy(4);   // free=0 → 전부 paid
        assertEquals(new EnergySplit(0, 4), paidCharge);
        assertEquals(6, paidUser.getPaidEnergy());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(paidUser));

        service.compensateEnergy(USER_ID, paidCharge, USERNAME);

        assertEquals(10, paidUser.getPaidEnergy(), "구 refundEnergy(int)는 free로 4를 채워 paid가 6에 머물렀다");
        assertEquals(0, paidUser.getFreeEnergy());
    }

    // ━━━━━━━━━━ 결함 1: compensateEnergy — 유저 직접 조회 ━━━━━━━━━━

    @Test
    @DisplayName("compensateEnergy: userRepository로 유저를 직접 조회해 환불한다 (ChatRoom 조회 금지)")
    void compensateEnergy_refundsViaUserRepository() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.compensateEnergy(USER_ID, charge, USERNAME);

        assertEquals(30, user.getEnergy());
        verify(userRepository).findById(USER_ID);
        verify(userRepository).save(user);
        verify(cacheService).evictUserProfile(USERNAME);
        // 핵심 회귀: roomId 자리에 userId를 넣던 ChatRoom 조회가 더 이상 없어야 한다
        verifyNoInteractions(chatRoomRepository);
    }

    @Test
    @DisplayName("compensateEnergy: 유저 미존재 시 예외 없이 로그만 남기고 캐시는 무효화한다")
    void compensateEnergy_userMissing_doesNotThrow() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.compensateEnergy(USER_ID, charge, USERNAME));

        verify(userRepository, never()).save(any());
        verify(cacheService).evictUserProfile(USERNAME);
    }

    // ━━━━━━━━━━ 결함 2: compensateFullRollback — 에너지 환불 포함 ━━━━━━━━━━

    @Test
    @DisplayName("compensateFullRollback: LLM/파싱 실패 경로에서 로그 삭제 + 에너지 환불을 모두 수행한다")
    void compensateFullRollback_refundsEnergy() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        var ctx = new ChatStreamServiceV2.RollbackContext(USER_ID, USERNAME, charge, "log-1");

        service.compensateFullRollback(ctx);

        verify(chatLogRepository).deleteById("log-1");
        assertEquals(30, user.getEnergy());  // TX-1 차감분 복구 — 기존 결함은 여기서 28로 소실
        verify(userRepository).save(user);
        verify(cacheService).evictUserProfile(USERNAME);
    }

    @Test
    @DisplayName("compensateFullRollback: Mongo 로그 삭제가 실패해도 에너지 환불은 수행된다")
    void compensateFullRollback_logDeleteFails_stillRefunds() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("mongo down")).when(chatLogRepository).deleteById("log-1");
        var ctx = new ChatStreamServiceV2.RollbackContext(USER_ID, USERNAME, charge, "log-1");

        assertDoesNotThrow(() -> service.compensateFullRollback(ctx));

        assertEquals(30, user.getEnergy());
        verify(userRepository).save(user);
        verify(cacheService).evictUserProfile(USERNAME);
    }

    @Test
    @DisplayName("compensateFullRollback: 오프닝 경로(energy=ZERO, savedUserLogId=null)는 삭제 없이 잔액 불변")
    void compensateFullRollback_openingPath_zeroCostNoop() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        var ctx = new ChatStreamServiceV2.RollbackContext(USER_ID, USERNAME, EnergySplit.ZERO, null);

        service.compensateFullRollback(ctx);

        verify(chatLogRepository, never()).deleteById(anyString());
        assertEquals(28, user.getEnergy());  // refundEnergy(ZERO)는 내부 가드로 no-op
        verify(cacheService).evictUserProfile(USERNAME);
    }
}
