package com.dongwoo.ticketing.queue;

/**
 * 대기열 입장 토큰 검증 실패. GlobalExceptionHandler 에서 403 으로 매핑.
 */
public class ReservationForbiddenException extends RuntimeException {
    public ReservationForbiddenException(String message) {
        super(message);
    }
}
