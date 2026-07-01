package com.spring.aichat.dto.admin;

/** 유저 디바이스/섀도우밴 조회 결과. */
public record DeviceInfoResponse(
    Long userId,
    String fingerprintMasked,
    boolean softBanned,
    Long deviceAccountCount
) {}
