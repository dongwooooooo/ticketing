# Stage 2 vs Stage 3 — 13 사양 매트릭스 비교

## 측정 환경

- Mac M2 Pro 16GB / Docker Desktop 10 cpu / 8 GB
- k6 v1.1.0 (host network, port 28091 = Stage 2 / 28092 = Stage 3)
- 부하 패턴: ramping-arrival-rate `100 → 500 → 1000 → 2000 → 3500 → 5000 RPS` (각 30s, peak 5000 RPS 30s, 총 ~3분 / 600k 요청)
- 좌석 카탈로그 SEAT_MAX = 50000

## 두 스테이지의 차이

| | Stage 2 (concurrency) | Stage 3 (queue) |
|---|---|---|
| 모듈 | ticketing/concurrency | ticketing/queue |
| 워크로드 | POST /seats/{id}/reservations 만 | POST /waiting/tokens → polling /waiting/tokens/{token} → POST /seats/{id}/reservations |
| admit gate | 없음 — backend가 직접 받아냄 | 100ms 마다 admit-rate-per-tick=100 (= 1000/s) 게이트 |
| 실패 모드 | 5xx + 매진 거절 + timeout | 토큰은 전원 발급, admit 게이트가 뒤로 보냄 |

Stage 3 admit-rate=1000/s 는 Stage 2 단일 노드 peak (~1200 req/s, a-4 / b-3 / c-3) 안에 안전 마진 20% 두고 설정.

## 13 사양 측정 결과

| 사양 | 구성 (backend+DB cpu) | S2 req/s | S2 success | S2 failed | S3 token rate (/s) | S3 reserve rate (/s) | S3 reserve success | S3 reserve failed | S3 admit_timeout | S3 admit_wait p95 (ms) |
|------|------|---------:|-----------:|----------:|---:|---:|---:|---:|---:|---:|
| a-1 | A 비례 1+1 | 547 | 39785 | 58237 | 235.9 | 147.2 | 27858 | 16784 | 0 | 27009 |
| a-2 | A 비례 2+2 | 1149 | 49097 | 151725 | 389.8 | 208.9 | 37822 | 32743 | 0 | 8593 |
| a-3 | A 비례 3+3 | 1163 | 49121 | 151405 | 527.7 | 240.1 | 42172 | 50527 | 0 | 3887 |
| a-4 | A 비례 4+4 | 1185 | 49194 | 154849 | 590.4 | 244.5 | 44178 | 62505 | 0 | 3614 |
| a-5 | A 비례 5+5 | 954 | 48143 | 117045 | 584.7 | 250.0 | 43376 | 58085 | 0 | 4287 |
| b-1 | B back=1 DB=4 | 582 | 42504 | 60217 | 349.0 | 196.0 | 35975 | 28101 | 0 | 9500 |
| b-2 | B back=2 DB=4 | 793 | 46863 | 91689 | 464.5 | 227.4 | 40397 | 42117 | 0 | 4394 |
| b-3 | B back=4 DB=4 | 1249 | 49344 | 167114 | 567.7 | 246.9 | 43068 | 55965 | 0 | 3012 |
| b-4 | B back=5 DB=4 | 948 | 48125 | 114955 | 596.1 | 252.0 | 43748 | 59743 | 0 | 4011 |
| c-1 | C back=4 DB=1 | 1121 | 48949 | 144970 | 608.8 | 253.3 | 44027 | 61785 | 0 | 3490 |
| c-2 | C back=4 DB=2 | 1087 | 48871 | 139893 | 541.2 | 243.6 | 42400 | 51803 | 0 | 4880 |
| c-3 | C back=4 DB=4 | 1179 | 49121 | 153899 | 565.9 | 244.9 | 43068 | 56439 | 0 | 3483 |
| c-4 | C back=4 DB=6 | 1054 | 48723 | 133000 | 574.0 | 248.6 | 43204 | 56561 | 0 | 3985 |

## 핵심 관찰

### 1. Stage 3 admit_timeout = 0 (전 사양)

- Stage 3 의 5000 RPS 부하에서도 **단 한 명도 30초 안에 admit 못 받아 떨어지지 않음**.
- Stage 2 에서 "failed" 로 잡힌 58k~167k 요청은 좌석 매진 거절 또는 HTTP 오류 — 사용자 입장에선 "오픈 5초 만에 못 들어갔다" 체험.
- Stage 3 에선 그 요청들이 큐에 적재되어 순서대로 처리됨. 단, 좌석이 50000 매진되면 그 이후 admit 통과한 사용자는 reserve_failed (seat sold out) 로 떨어짐.

### 2. Stage 3 reserve rate = 147~253 RPS (admit-rate=1000/s 안에서)

- 모든 사양에서 admit-rate=1000/s 게이트로 시간당 reserve 처리량 상한이 묶임.
- 실제 측정된 reserve rate 가 300/s 도 안 되는 이유: 좌석 매진(50000) 후 admitted 사용자가 reserve 시도해도 fail 처리되어 success 카운트로 안 잡힘. duration 187초 중 후반 ~100초는 매진 상태.
- 사양 변화에 따른 reserve rate 차이 (a-1 147 → a-4 244) 는 backend cpu 가 결정 — 초기 매진까지의 처리 속도.

### 3. Stage 3 token issue rate = backend cpu 비례

- a-1 (1cpu) 236/s → a-4 (4cpu) 590/s → a-5 (5cpu) 585/s (plateau).
- 5000 RPS 부하에서 token issue 가 backend cpu 에 비례 — token 발급 자체는 in-memory write 라 DB 영향 없음.
- a-1 에서 token_failed=1838 (4.1%): 1cpu backend 가 5000 RPS 토큰 발급 트래픽도 못 받아냄 (HTTP timeout).

### 4. admit_wait p95 = backend cpu 비례 감소

- a-1 (1cpu): admit_wait p95 = 27초 — 사용자 4명 중 1명이 27초 기다림.
- a-4 (4cpu): admit_wait p95 = 3.6초 — 같은 부하에서 7배 빠른 큐 소화.
- 큐 자체는 in-memory 라 빠른데, p95 가 길어지는 건 status 폴링 자체가 1초 sleep + 폴링 처리 backend cpu 점유. backend cpu 늘리면 폴링도 빨라지고 admit dispatcher 도 빨라짐.

### 5. Stage 2 vs Stage 3 — 사용자가 체감하는 결과

| 사양 | Stage 2 | Stage 3 |
|------|---------|---------|
| a-1 (1+1cpu) | 547 RPS 처리 + **사용자 5.8만명 튕김** | 큐 적재, **2.7만명 좌석 확보**, 0명 대기 timeout, p95 27초 대기 |
| a-4 (4+4cpu) | 1185 RPS 처리 + **사용자 15.5만명 튕김** | 큐 적재, **4.4만명 좌석 확보**, 0명 대기 timeout, p95 3.6초 대기 |

Stage 2 에서 "튕긴" 사용자는 그냥 못 들어간 사람이고, Stage 3 에서 "기다린" 사용자는 좌석 매진되기 전까지 줄서서 들어간 사람.

### 6. Stage 2 의 "성공 50000" = SEAT_MAX 캡 (Stage 3 도 동일)

- a-2 이상 모든 사양에서 Stage 2 success ≈ 49100~49344. 5분간 좌석이 모두 매진된 결과.
- Stage 3 reserve_success 도 27858~44178. backend cpu 가 클수록 매진 속도 빠름.
- 즉 두 스테이지 모두 절대 처리량의 상한은 SEAT 카탈로그 크기. **차이는 좌석을 분배받지 못한 사용자가 어떻게 떨어지는가**.

## 결론

Stage 2 = 1200 RPS 처리 + 초과분 5xx 거절. Stage 3 = 1000 RPS 처리 + 초과분 큐 대기 후 순차 처리 (대기 timeout 0). 절대 처리량은 admit-rate 가 결정하므로 Stage 3 가 Stage 2 보다 throughput 이 높을 수 없다. Stage 3 의 가치는 throughput 이 아니라 **사용자 행동 5000 RPS 를 흡수해 0명 timeout** 으로 받아내는 것.

## Raw 파일

- 각 사양: `results/{spec}/k6-stdout.txt`, `summary.json`, `docker-stats-stream.txt`, `meta.txt`
- 빠진 파일: `actuator-prometheus.txt`, `app-logs.txt` — k6 threshold-cross 로 인한 `set -e` 조기 종료. 다음 사양 시작 전 compose down 으로 환경은 정리됨. Stage 2 와 동일 패턴.
