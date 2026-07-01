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

    long countByStatus(SupportTicketStatus status);
}
