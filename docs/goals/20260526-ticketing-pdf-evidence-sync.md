# 2026-05-26 Ticketing PDF / GitHub Evidence / Notion 정합화

## 목표

제출용 PDF를 기준으로 `Ticketing Concurrency Lab`의 GitHub README, 상세 테스트 문서, Notion 페이지를 정리한다.

PDF는 제출자가 처음 보는 요약본이고, GitHub `docs/evidence/*.md`는 PDF의 `상세 테스트` 링크를 클릭했을 때 확인하는 검증 근거다. Notion은 PDF의 흐름과 GitHub의 상세 근거를 합친 제출용 허브로 정리한다.

## 기준 우선순위

1. 제출용 PDF
   - `/Users/idong-u/Downloads/lee-dongwoo-portfolio.pdf`
   - `/Users/idong-u/resume/ahnlab-ai-service-portfolio/final-portfolio/lee-dongwoo-portfolio.pdf`
2. PDF 생성 원본
   - `/Users/idong-u/resume/ahnlab-ai-service-portfolio/final-portfolio/build-notion-portfolio.mjs`
3. GitHub 제출 repo
   - `/Users/idong-u/d/ticketing`
   - 공개 URL: `https://github.com/dongwooooooo/ticketing`
4. 관측/부하테스트 repo
   - `/Users/idong-u/d/ticketing-observability`
   - 공개 URL: `https://github.com/dongwooooooo/ticketing-observability`
5. Notion 제출 허브
   - `https://www.notion.so/Ticketing-Concurrency-Lab-36373344235881fdb466f9b0636095df`

## 문서 역할

| 문서 | 역할 | 작성 수준 |
| --- | --- | --- |
| PDF | 제출용 요약 | 문제, 선택지, 결과를 짧게 보여준다. |
| `README.md` | GitHub 첫 화면 | PDF 흐름을 유지하고 상세 근거 문서로 연결한다. |
| `docs/evidence/README.md` | 상세 근거 인덱스 | 각 부하테스트별 상세 문서, 테스트 코드, k6 원본, Grafana/Prometheus 원본 위치를 연결한다. |
| `docs/evidence/*.md` | PDF 상세 테스트 링크 대상 | 테스트 목적, 테스트 코드 링크, 실행 명령, 실행 결과, k6 결과, 원본 경로를 한 문서 안에서 보여준다. |
| Notion | 제출용 허브 | PDF 요약과 GitHub 상세 근거 링크를 함께 배치한다. |

## 상세 테스트 문서 완료 기준

### 1. `docs/evidence/seat-reservation-contention.md`

PDF의 `부하테스트 및 개선 1. 좌석 예약 경합` 상세 링크 대상이다.

포함해야 할 내용:

- 테스트 목적
  - 동일 좌석 동시 요청에서 최종 예약이 1건만 생성되는지 확인한다.
  - 비관적 락과 상태 조건 기반 UPDATE의 p99, 처리량을 비교한다.
- 테스트 코드 링크
  - `basic/src/test/java/com/dongwoo/ticketing/repro/SeatRaceReproTest.java`
  - `concurrency/src/test/java/com/dongwoo/ticketing/concurrency/SeatLockConcurrencyTest.java`
  - 필요한 경우 `concurrency/src/test/java/com/dongwoo/ticketing/concurrency/PaymentIdempotencyConcurrencyTest.java`
  - 필요한 경우 `concurrency/src/test/java/com/dongwoo/ticketing/concurrency/ExpiryPaymentRaceTest.java`
- 실행 명령
  - 관련 Gradle test command를 명시한다.
- 단위 테스트 결과
  - 테스트명, 입력 조건, 성공/거절/최종 예약 수를 표로 정리한다.
  - `seatReservationRace total=100 success=1 rejected=99 heldCount=1` 같은 로그를 근거로 남긴다.
- 성능 비교 결과
  - PDF에 들어간 p99 `586ms -> 192ms`, 처리량 `1490 -> 4219 ops/s`의 원본 근거를 연결한다.
  - `docs/stage3-entry-rationale.md`와 별도 측정 repo 경로를 연결한다.
- 결론
  - 최종 예약 1건을 유지하면서 비관적 락보다 p99와 처리량이 개선된 근거로 상태 조건 기반 UPDATE와 조건부 유니크 인덱스를 선택했다고 정리한다.

### 2. `docs/evidence/opening-surge-queue.md`

PDF의 `부하테스트 및 개선 2. 예매 오픈 피크 트래픽` 상세 링크 대상이다.

포함해야 할 내용:

- 테스트 목적
  - 5K RPS 피크 요청에서 대기열 미적용과 메모리 대기열 적용 결과를 비교한다.
  - 처리 한도를 넘은 요청이 실패 응답으로 끝나는지, 대기 상태로 유지되는지 확인한다.
- 테스트 코드 링크
  - `queue/src/test/java/com/dongwoo/ticketing/QueueLoadTest.java`
  - `queue/src/test/java/com/dongwoo/ticketing/HappyPathIntegrationTest.java`
  - 필요하면 `queue/src/main/java/com/dongwoo/ticketing/queue/*`
- k6 스크립트 링크
  - `ticketing-observability/stage3-capacity/k6/capacity-probe.js`
  - 필요하면 `ticketing-observability/k6/scripts/stage2-baseline.js`
  - 필요하면 `ticketing-observability/k6/scripts/stage3-queue.js`
- k6 원본 결과 링크
  - `ticketing-observability/stage3-capacity/results/*/summary.json`
  - `ticketing-observability/stage3-capacity/results/comparison.md`
  - `ticketing-observability/stage3-capacity/results/comparison.csv`
- 수치 근거
  - 대기열 미적용: 좌석 예약 요청 `230,858건`, 성공 `49,492건`, 실패 요청 `181,366건`
  - 메모리 대기열 적용: 토큰 발급 `119,683건`, 대기열 통과 `119,683건`, 좌석 예약 성공 `45,488건`, 대기 중 제한 시간 초과 `0건`, 대기 시간 p95 `4.37초`
- 시각 자료 경로
  - `ticketing-observability/screenshots/portfolio-evidence/selected/02-opening-surge-dropped-failed.png`
  - `ticketing-observability/screenshots/portfolio-evidence/selected/02-opening-surge-wait-total-latency.png`
- 결론
  - 대기열은 처리량을 무리하게 올리는 장치가 아니라, 피크 요청을 실패 응답 대신 대기 상태로 받기 위한 구조로 정리한다.

### 3. `docs/evidence/redis-multi-instance.md`

PDF의 `부하테스트 및 개선 3. Redis 기반 멀티 인스턴스 확장` 상세 링크 대상이다.

포함해야 할 내용:

- 테스트 목적
  - 메모리 대기열의 단일 인스턴스 한계를 확인한다.
  - Redis 기반 대기열과 좌석 락이 멀티 인스턴스에서 같은 상태를 공유하는지 확인한다.
  - 단일 스펙업과 멀티 인스턴스 구성의 응답시간, DB connection, Hikari pending을 비교한다.
- 테스트 코드 링크
  - `distributed/src/test/java/com/dongwoo/ticketing/DistributedQueueTest.java`
  - `distributed/src/test/java/com/dongwoo/ticketing/DistributedSeatLockTest.java`
  - `distributed/src/test/java/com/dongwoo/ticketing/FencingTokenTest.java`
  - 필요하면 `distributed/src/test/java/com/dongwoo/ticketing/OutboxReconciliationTest.java`
- 단위 테스트 결과
  - cross-instance token visibility
  - duplicate admit 방지
  - FIFO 대기열
  - Redis seat lock
  - fencing token
- k6 스크립트 링크
  - `ticketing-observability/stage4-capacity/k6/opening-surge.js`
  - `ticketing-observability/stage4-capacity/k6/scale-comparison.js`
- k6 원본 결과 링크
  - `stage4-single-opening-portfolio-single-1x2-pool10-r2.summary.json`
  - `stage4-single-opening-portfolio-single-1x4-pool20-r1.summary.json`
  - `stage4-dual-opening-portfolio-dual-2x2-pool10-r1.summary.json`
- Prometheus/Grafana 원본 경로
  - `ticketing-observability/screenshots/portfolio-evidence/prometheus-timeseries/stage4-single-opening-portfolio-single-1x2-pool10-r2.json`
  - `ticketing-observability/screenshots/portfolio-evidence/prometheus-timeseries/stage4-single-opening-portfolio-single-1x4-pool20-r1.json`
  - `ticketing-observability/screenshots/portfolio-evidence/prometheus-timeseries/stage4-dual-opening-portfolio-dual-2x2-pool10-r1.json`
  - `ticketing-observability/screenshots/portfolio-evidence/selected/03-redis-multi-instance-hikari-active-pending.png`
  - `ticketing-observability/screenshots/portfolio-evidence/selected/03-redis-multi-instance-redis-postgres-load.png`
  - `ticketing-observability/screenshots/portfolio-evidence/selected/03-redis-distributed-state-gradle-report.png`
- 수치 근거
  - `1대 x 2 CPU / pool 10`
  - `1대 x 4 CPU / pool 20`
  - `2대 x 2 CPU / pool 10 x 2`
  - 각 구성의 토큰/통과/예약 성공, 토큰 발급 실패, Hikari pending max, PostgreSQL connection max, 전체 p95
- 결론
  - 로컬 테스트에서는 응답시간만 보면 단일 인스턴스 스펙업이 유리했다는 점을 명확히 쓴다.
  - 그럼에도 티켓팅 서버는 예매 오픈 직후 장애와 예측 초과 트래픽이 운영 리스크가 되므로, 장애 영향 범위 축소와 멀티 인스턴스 상태 공유를 위해 Redis 기반 구조를 선택했다고 정리한다.
  - 서버를 늘리면 DB connection, Redis 요청량, 네트워크, 모니터링, 장애 대응 비용도 함께 증가하므로 운영 비용과 저장소 계층 확장은 별도 고려 대상임을 명시한다.

## Notion 완료 기준

Notion은 PDF와 GitHub를 합친 제출 허브로 정리한다.

- 상단은 PDF와 같은 요약 구조를 유지한다.
- 각 부하테스트 제목 옆 또는 하단에 GitHub `docs/evidence/*.md` 상세 링크를 둔다.
- PDF에는 들어가지 않는 상세 단위 테스트 로그와 k6 원본 경로는 Notion에서 요약하고 GitHub evidence로 연결한다.
- 기존 Stage 하위 페이지는 상세 기록으로 남기되, 메인 페이지의 큰 흐름은 `부하테스트 및 개선 1~3`으로 맞춘다.

## GitHub evidence 인덱스 완료 기준

`docs/evidence/README.md`는 다음을 한 번에 찾을 수 있어야 한다.

- PDF 상세 테스트 링크 대상 문서
- 관련 단위 테스트 코드
- 관련 k6 스크립트
- 관련 summary JSON
- 관련 Grafana/Prometheus 캡처
- 재현 명령과 원본 레포 링크

## 작성 금지 / 주의

- PDF에 없는 확정되지 않은 수치를 본문 결론처럼 쓰지 않는다.
- 상세 근거 문서에는 원본 경로를 숨기지 않는다.
- 단위 테스트와 k6 결과를 한 표에 섞을 때는 `검증 성격`을 구분한다.
- 사용자가 금지한 표현은 제출 문서 본문에 쓰지 않는다.
- 가능성을 열어두는 모호한 표현이나 기준이 불명확한 표현은 쓰지 않는다.

## 실행 계획

1. PDF 링크 대상 세 문서의 현재 누락사항을 점검한다.
2. 단위 테스트 코드, 실행 로그, k6 결과, Grafana/Prometheus 자료를 파일별로 매핑한다.
3. `docs/evidence/README.md`를 evidence 인덱스로 보강한다.
4. `docs/evidence/seat-reservation-contention.md`를 단위 테스트와 p99/처리량 근거 중심으로 보강한다.
5. `docs/evidence/opening-surge-queue.md`를 k6 stage3 결과와 시각 자료 경로 중심으로 보강한다.
6. `docs/evidence/redis-multi-instance.md`를 단위 테스트, k6 stage4 결과, Prometheus/Grafana 자료 중심으로 보강한다.
7. README의 상세 근거 링크가 보강된 evidence 문서로 이어지는지 확인한다.
8. Notion 메인 페이지를 PDF 요약과 GitHub 상세 근거가 함께 보이는 구조로 갱신한다.
9. 금지 표현, 링크 경로, 수치 일치 여부를 검증한다.

## 검증 명령

```bash
git diff --check
rg -n "<사용자가 금지한 표현 패턴>" README.md docs/evidence || true
for p in \
  docs/evidence/seat-reservation-contention.md \
  docs/evidence/opening-surge-queue.md \
  docs/evidence/redis-multi-instance.md \
  docs/evidence/README.md; do
  test -f "$p" || echo "missing $p"
done
```

## 완료 조건

- PDF의 세 `상세 테스트` 링크가 모두 실제 테스트 코드와 결과를 포함한 GitHub 문서로 연결된다.
- GitHub evidence 인덱스에서 단위 테스트, k6, Grafana/Prometheus 원본 경로를 바로 찾을 수 있다.
- Notion 메인 페이지에서 PDF 요약과 GitHub 상세 근거가 같이 보인다.
- README는 PDF 수준을 넘는 장황한 설명 없이 상세 근거로 연결한다.
