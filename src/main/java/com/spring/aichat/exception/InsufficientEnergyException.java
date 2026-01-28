package com.spring.aichat.exception;

public class InsufficientEnergyException extends BusinessException {
    public InsufficientEnergyException(String message) {
        super(ErrorCode.INSUFFICIENT_ENERGY, message);
    }
}
