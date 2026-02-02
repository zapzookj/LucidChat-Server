package com.spring.aichat.dto.chat;

public record ChatRoomInfoResponse(Long id,
                                   String characterName,
                                   String defaultImageUrl,
                                   String backgroundImageUrl,
                                   int affectionScore,
                                   String statusLevel) {
}
