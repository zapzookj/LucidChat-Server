package com.spring.aichat.domain.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportTicketReplyRepository extends JpaRepository<SupportTicketReply, Long> {

    List<SupportTicketReply> findByTicket_IdOrderByIdAsc(Long ticketId);
}
