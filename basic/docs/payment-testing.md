# 결제 테스트 전략

실제 PG(토스/포트원/Stripe) 차감 없이 결제 흐름을 검증하는 방법. 업계 패턴 5가지 + 본 Lab 선택.

## TL;DR

- Stage 1~3: 자체 mock callback (현재 방식). 외부 의존 0
- Stage 4 또는 별도: WireMock 또는 stripe-mock 컨테이너로 실제 HTTP + 지연/실패 주입
- 실 PG 통합 검증: 토스/포트원/Stripe **test mode** 키 + 테스트 카드번호
- 운영 신뢰성 검증: contract testing (Pact) 또는 record/replay

## 패턴 1 — In-process mock callback (본 Lab 현재 채택)

본 레포 `MockPaymentGateway`. PG 호출 없이 우리 서버가 우리 자신에게 callback.

```java
@Async
public void firePaymentCallback(Long paymentId) {
    Thread.sleep(1000);
    boolean success = ThreadLocalRandom.current().nextDouble() < successRate;
    restTemplate.postForEntity(callbackUrl, body, Void.class);
}
```

| 장점 | 단점 |
|---|---|
| 외부 의존 0 | 실 PG 응답 형식 학습 안 됨 |
| 빠름 (1s sleep 조정 가능) | 네트워크 지연/timeout 시나리오 약함 |
| 환경변수로 success-rate/duplicate-callbacks 제어 | PG 실제 사양 변경 못 따라잡음 |
| 테스트 결정성 높음 | "운영 PG 통합 검증" 신호 약함 |

본 Lab은 동시성 + 멱등성 측정이 목적이라 충분. 면접 답변 시 "PG mock 자체 발사, 실 PG 통합은 별도 단계로 분리"로 정직 표명.

## 패턴 2 — WireMock 컨테이너 (HTTP mock server)

http://wiremock.org/

별도 프로세스로 HTTP mock server 띄움. 시나리오별 응답 stubbing.

```java
@SpringBootTest
@WireMockTest(httpPort = 8089)
class PaymentIntegrationTest {

    @Test
    void mockApproval() {
        stubFor(post("/v1/payments/approve")
            .willReturn(okJson("{\"status\":\"DONE\",\"paymentKey\":\"...\"}")));

        // 우리 서비스 호출 → WireMock가 응답
        paymentService.requestApproval(...);
    }

    @Test
    void mockTimeout() {
        stubFor(post("/v1/payments/approve")
            .willReturn(aResponse().withFixedDelay(30000))); // 30초 hang

        // timeout 시나리오 검증
    }
}
```

| 장점 | 단점 |
|---|---|
| 실제 HTTP 통신 | 별도 컨테이너 운영 |
| 지연/실패/타임아웃 주입 자유 | 응답 형식 직접 정의해야 함 |
| 같은 코드로 운영 PG → WireMock 교체 가능 | 토스/포트원 사양 변경 시 stub 수동 갱신 |

본 Lab Stage 4에서 도입 검토 (다중 인스턴스 + 결제 cascade 시나리오).

## 패턴 3 — PG 제공 sandbox / test mode

대부분 PG가 **test mode 키** 제공. 실제 API 호출하지만 차감/송금 **X**.

### 토스페이먼츠

- 테스트 클라이언트 키: `test_ck_*`
- 테스트 시크릿 키: `test_sk_*`
- 테스트 카드 번호: 공식 문서 (https://docs.tosspayments.com/reference/test#테스트-카드-번호)
  - 성공: `4330-0000-0000-0010`
  - 한도 초과 실패: 별도 카드 번호
  - 분실 카드 실패: 별도

```bash
curl -X POST https://api.tosspayments.com/v1/payments/confirm \
  -H "Authorization: Basic $(echo -n 'test_sk_xxx:' | base64)" \
  -H "Content-Type: application/json" \
  -d '{"paymentKey":"...", "orderId":"...", "amount":1000}'
```

### 포트원

- 가맹점 식별 콘솔에서 **테스트 모드** 활성화
- 실 PG 대신 포트원 시뮬레이터로 라우팅
- 결제창에서 카드 정보 입력 불필요 (테스트용 결제 토큰 자동 발급)

### Stripe

- `sk_test_*` 키
- 테스트 카드: `4242 4242 4242 4242` (성공), `4000 0000 0000 0002` (decline) 등 (https://stripe.com/docs/testing)
- 임의 미래 만료 + 임의 CVC

| 장점 | 단점 |
|---|---|
| 운영 PG 사양 100% 동일 | PG 계정 + 사업자 인증 필요 (개인 어려움) |
| 응답 형식 검증 | 비동기 callback 받으려면 public URL (ngrok 필요) |
| 실수로 운영 키 쓰는 사고 방어 | 테스트 환경 자체 장애 시 막힘 |

본 Lab Stage 1~4에서는 사용 안 함 (개인 토이 프로젝트라 PG 계약 X). 면접 답변 시 "실 PG 통합은 test mode 키 + 테스트 카드 번호로 검증한다"고 설명.

## 패턴 4 — Vendor-provided local mock (e.g. stripe-mock)

https://github.com/stripe/stripe-mock

Stripe 공식 mock server. 컨테이너로 띄우면 Stripe API 응답을 그대로 시뮬레이션.

```bash
docker run -d -p 12111:12111 -p 12112:12112 stripe/stripe-mock
```

토스/포트원은 공식 mock 미제공 → 패턴 2 (WireMock)으로 대체.

| 장점 | 단점 |
|---|---|
| 응답 형식 자동 일치 | Stripe만 제공 (한국 PG 없음) |
| 컨테이너 1개 | 비즈니스 로직(잔액 차감 등) 없음 |

## 패턴 5 — Contract testing (Pact)

https://pact.io/

PG ↔ 우리 서비스 사이 **계약(contract)**을 양쪽이 합의. 변경 시 양쪽 깨짐 감지.

```
[Consumer test in our service]
  → Pact mock server (stub PG response)
  → Pact 파일 생성 (request/response 합의)

[Provider test in PG side (or replay)]
  → Pact 파일 재생
  → PG가 실제 그 응답을 주는지 검증
```

| 장점 | 단점 |
|---|---|
| PG 사양 변경 즉시 감지 | PG 측 협조 필요 (외부 PG는 불가) |
| 양방향 자동 검증 | 학습 곡선 |

본 Lab 적용 안 함. 외부 PG는 우리가 contract 강제 불가. 사내 결제 마이크로서비스 분리 시 의미 있음.

## 본 Lab 단계별 적용

| Stage | 패턴 | 이유 |
|---|---|---|
| basic | 1 (in-process mock) | 동시성 메시지 집중, 외부 의존 제거 |
| concurrency | 1 + 환경변수 시나리오 (duplicate-callbacks, success-rate) | 멱등성/만료-결제 race 시나리오 직접 트리거 |
| queue | 1 유지 | 대기열 메시지 집중, PG 의존 X |
| distributed | 2 (WireMock) 도입 검토 | timeout/cascade 시나리오에 실제 HTTP 지연 필요 |

운영 통합 단계 (Lab 범위 밖):
1. 토스페이먼츠 또는 포트원 가맹 계약
2. test mode 키 발급
3. 결제창 SDK 통합
4. webhook URL 등록 (ngrok 로컬 expose)
5. 테스트 카드로 success/fail/timeout 시나리오 검증
6. 운영 키 교체 + 모니터링 + audit log

## 실 PG callback 수신 시 추가 고려

운영 도입 시 mock과 다른 점:

| 항목 | mock | 실 PG |
|---|---|---|
| Public URL | localhost OK | https + 인증서 + IP allowlist |
| 서명 검증 | 없음 | HMAC-SHA256 (토스), RSA (포트원) 헤더 검증 |
| 재시도 정책 | 자체 발사 | PG가 exponential backoff (포트원 10s/30s timeout, 최대 N회) |
| 순서 보장 | 단일 발사 | 무보장 — Webhook 도착 순서가 PG 측 처리 순서와 다를 수 있음 |
| 중복 도착 | 환경변수 통제 | PG 재시도로 자연 발생 |
| 응답 시간 SLA | 1초 sleep 흉내 | < 30초 응답 안 하면 PG 재시도 |

이 차이 때문에 본 Lab의 idempotency-key + atomic UPDATE 패턴이 그대로 실 PG에서도 유효.

## 면접 답변 흐름

면접관 "결제 어떻게 테스트하셨어요?":

1. "본 Lab은 in-process mock callback으로 동시성 + 멱등성 검증에 집중했습니다"
2. "실 PG 통합 단계는 토스페이먼츠 또는 포트원의 test mode 키 + 테스트 카드 번호로 검증합니다 (https://docs.tosspayments.com/reference/test)"
3. "운영 환경 cascade 시나리오는 WireMock 컨테이너로 timeout/지연/실패를 주입해서 검증할 계획입니다"
4. "본 Lab의 idempotency-key UNIQUE + INSERT ON CONFLICT 패턴은 실 PG에서도 그대로 유효 (PG가 retry로 중복 callback 보내는 사양이라)"

면접관 추가 질문 "webhook 서명 검증은요?":
- "토스는 HMAC-SHA256, 포트원은 RSA 헤더 검증. 본 Lab mock에는 없지만 운영 통합 시 callback handler 진입 직후 서명 검증 필터 추가"

면접관 "환불은 어떻게?":
- "본 Lab Stage 1~3 범위 밖. 환불 자동화는 Stage 3+ 또는 별도 단계. PG 환불 API는 result-based + idempotency-key (토스 사양)으로 운영 시 같은 패턴 적용"

## 참고 URL

- 토스페이먼츠 테스트 가이드: https://docs.tosspayments.com/reference/test
- 토스 idempotency-key: https://docs.tosspayments.com/guides/using-api/idempotency-key
- 포트원 Webhook: https://portone.gitbook.io/docs/result/webhook
- 포트원 테스트 환경: https://portone.gitbook.io/docs/console/test-payment
- Stripe testing: https://stripe.com/docs/testing
- WireMock: http://wiremock.org/
- stripe-mock: https://github.com/stripe/stripe-mock
- Pact: https://pact.io/
- Airbnb double payments (실 사례): https://medium.com/airbnb-engineering/avoiding-double-payments-in-a-distributed-payments-system-2981f6b070bb
