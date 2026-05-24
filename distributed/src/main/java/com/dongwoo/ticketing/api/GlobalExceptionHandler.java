package com.dongwoo.ticketing.api;

import com.dongwoo.ticketing.queue.ReservationForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ReservationForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ReservationForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "waiting_token_required", "message", e.getMessage()));
    }
}
