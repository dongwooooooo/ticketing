package com.dongwoo.ticketing.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 2 Deep Dive — 매진(SOLD/HELD)된 좌석 ID를 application-level Set에 보관.
 *
 * 시나리오 #10: 매진 후 봇 트래픽 5000 TPS로 같은 좌석 반복 클릭.
 *   - fast path 부재 시: 모든 요청 SELECT FOR UPDATE → HikariCP 대기 → 정상 사용자 영향
 *   - fast path 적용 시: 봇 요청은 Set.contains() 만으로 즉시 거절 → DB 진입 X
 *
 * 활성화: `fast-path` 프로파일 또는 SoldOutCacheConfig 등록 시.
 * 비활성화: 메서드는 항상 호출 가능하지만 Set이 비어 있어 차단 효과 0.
 *   (테스트 격리를 위해 항상 빈으로 노출하고, 채워 넣는 시점을 프로파일로 분리)
 *
 * 한계 (의도된 trade-off):
 *   - 환불로 다시 AVAILABLE이 되면 remove() 호출 누락 시 정상 좌석을 거짓 거절
 *   - 분산 환경에서는 Redis SET 등으로 교체 필요 (Stage 3 큐 도입 후 검토)
 */
@Component
public class SoldOutCache {

    private final Set<Long> soldOutSeats = ConcurrentHashMap.newKeySet();

    /** 매진 마킹. 결제 완료/HELD 진입 시 호출. */
    public void markSoldOut(Long seatId) {
        soldOutSeats.add(seatId);
    }

    /** 환불/HELD 만료 시 호출 — 좌석 풀림. */
    public void release(Long seatId) {
        soldOutSeats.remove(seatId);
    }

    public boolean isSoldOut(Long seatId) {
        return soldOutSeats.contains(seatId);
    }

    /** 테스트용 강제 초기화. */
    public void clear() {
        soldOutSeats.clear();
    }

    public int size() {
        return soldOutSeats.size();
    }
}
