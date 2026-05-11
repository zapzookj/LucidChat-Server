package com.spring.aichat.domain.chat;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * [Phase6/Tier3 / C-9] ChatLogDeadletter MongoRepository.
 */
public interface ChatLogDeadletterRepository extends MongoRepository<ChatLogDeadletter, String> {
}
