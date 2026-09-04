package com.spring.aichat.domain.support;

import com.spring.aichat.domain.enums.SupportTicketStatus;
import com.spring.aichat.domain.enums.SupportTicketType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByUser_IdOrderByIdDesc(Long userId);

    Page<SupportTicket> findAllByOrderByIdDesc(Pageable pageable);

    Page<SupportTicket> findByStatusOrderByIdDesc(SupportTicketStatus status, Pageable pageable);

    Page<SupportTicket> findByTypeOrderByIdDesc(SupportTicketType type, Pageable pageable);

    /** [E-6.2] 상태+유형 동시 필터. 종전엔 else-if 사슬이라 status가 있으면 type이 조용히 무시됐다. */
    Page<SupportTicket> findByStatusAndTypeOrderByIdDesc(SupportTicketStatus status, SupportTicketType type, Pageable pageable);

    long countByStatus(SupportTicketStatus status);
}
