package com.dongwoo.ticketing.api;

import com.dongwoo.ticketing.api.dto.ReservationResponse;
import com.dongwoo.ticketing.queue.ReservationForbiddenException;
import com.dongwoo.ticketing.queue.WaitingQueue;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.service.ReservationService;
import com.dongwoo.ticketing.support.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;
    private final AuthContext authContext;
    private final WaitingQueue waitingQueue;

    /**
     * Stage 3 — 대기열 게이트 적용.
     * X-Waiting-Token 헤더 필수. 토큰이 admitted 가 아니면 403.
     * 일단 one-shot 아님 — 통과 후에도 토큰은 TTL(5분)까지 유효.
     */
    @PostMapping("/seats/{seatId}/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable Long seatId,
            @RequestHeader(value = "X-Waiting-Token", required = false) String waitingToken,
            HttpServletRequest request) {
        if (waitingToken == null || waitingToken.isBlank() || !waitingQueue.isAdmitted(waitingToken)) {
            throw new ReservationForbiddenException("X-Waiting-Token missing or not admitted");
        }
        String userId = authContext.currentUserId(request);
        var reservation = reservationService.reserve(seatId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse get(@PathVariable Long reservationId) {
        var reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
        return ReservationResponse.from(reservation);
    }

    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<Void> cancel(
            @PathVariable Long reservationId,
            HttpServletRequest request) {
        String userId = authContext.currentUserId(request);
        reservationService.cancel(reservationId, userId);
        return ResponseEntity.noContent().build();
    }
}
