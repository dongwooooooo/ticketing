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

## 부하 테스트에 PG test mode 쓰면 안 되는 이유

| PG | test 환경 부하 정책 |
|---|---|
| 토스페이먼츠 | 약관 "비정상 트래픽 차단". 발견 시 키 정지 / 가맹 계정 정지 가능 |
| 포트원 | 동일. IP 차단 |
| Stripe | 공식 가이드 "do not load test against test mode" (https://docs.stripe.com/rate-limits). 100 req/s 이상 throttle, 더 가면 차단 |

이유: PG test 서버도 운영 인프라 공유 + 다른 가맹점과 공용. 자사 SLA 보호 + 악용 방지. **포트폴리오 시연이라도 절대 X**.

→ 만 단위 부하 테스트는 **반드시 자체 mock**. 실 PG는 응답 형식·서명·idempotency 사양 검증용 단발만.

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

## 패턴 4 — Vendor-provided local mock

### stripe-mock (공식)

https://github.com/stripe/stripe-mock

```bash
docker run -d -p 12111:12111 -p 12112:12112 stripe/stripe-mock
```

### 토스 공식 self-hosted mock — **없음 (확인 결과)**

토스 공식 GitHub 8개 repo (2026-05 기준):

| repo | 종류 |
|---|---|
| payment-sdk-ios, payment-sdk-android, browser-sdk | SDK |
| tosspayments-sample, payment-samples, tosspayments-sample-v1 | 통합 sample 코드 |
| brandpay-sdk-android-sample, BrandPay | BrandPay SDK |

모두 SDK 또는 sample 통합 예시. **공식 mock server / Docker 이미지 없음**.

토스 공식 테스트 옵션:
1. **클라우드 sandbox** (https://developers.tosspayments.com/sandbox) — test mode 키 + 테스트 카드 + webhook URL 등록. 부하 테스트 금지
2. **test mode API 키** (`test_ck_*`, `test_sk_*`) — 실 API 호출, 차감 X
3. **테스트 카드 번호** (https://docs.tosspayments.com/reference/test)

자체 호스팅 mock이 필요하면 커뮤니티 옵션 사용 (아래).

### 한국 PG 사실상 표준 — samchon/payments (커뮤니티, **MIT, 활성**)

https://github.com/samchon/payments

토스 공식이 self-hosted mock을 제공하지 않으므로 사실상 표준. 토스페이먼츠 + 아임포트(포트원) 둘 다 mockup 서버 제공. **실 SDK 호환** — host만 mock으로 바꾸면 동일 코드 동작.

| 항목 | 내용 |
|---|---|
| 라이선스 | MIT |
| 활성도 | v10.0.0 (2025-03), 362★ |
| 언어 | TypeScript / NestJS |
| 패키지 | `fake-toss-payments-server`, `fake-iamport-server`, `payment-backend`(통합 PG MSA), `toss-payments-server-api`(SDK), `iamport-server-api`(SDK) |
| 포트 | fake-toss 30771 |
| Webhook | `FakeTossConfiguration.WEBHOOK_URL` 설정으로 우리 서버 callback URL 지정 |
| 토스 API | `key_in`, `approve`, `billing`, `cancel` 등 실 사양 |
| Docker | 공식 이미지 미공개 → npm 또는 자체 Dockerfile |

빠른 실행:

```bash
git clone https://github.com/samchon/payments
cd payments/packages/fake-toss-payments-server
npm install
npm run build
npm run start    # → http://localhost:30771
```

본 Lab과 연결:

```typescript
// fake-toss-payments-server 설정 (npm module로 사용 시)
import FakeToss from "fake-toss-payments-server";
FakeToss.FakeTossConfiguration.WEBHOOK_URL = "http://localhost:8080/payments/callback";
```

우리 ticketing 서버는 토스 SDK로 결제 호출:

```java
// Stage 3+ 도입 시 PaymentService 변형 (의사 코드)
RestTemplate toss = new RestTemplate();
ResponseEntity<TossPaymentResponse> resp = toss.exchange(
    "http://localhost:30771/v1/payments/confirm",
    HttpMethod.POST,
    new HttpEntity<>(body, headers),  // headers에 Idempotency-Key
    TossPaymentResponse.class
);
// 1초 후 fake 서버가 우리 /payments/callback 으로 webhook 발사
```

장점:
- 실 토스 API 형식 그대로 (signature, idempotency-key, response shape)
- 만 단위 부하 가능 (Node.js 컨테이너 자원까지)
- 면접 답변 "토스 SDK + 자체 mock 서버로 운영 통합 흐름 검증"

단점:
- 우리 PaymentService 코드를 토스 SDK 형식으로 마이그레이션 필요
- Node.js 별도 띄움

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
| queue | **4 (samchon/payments fake-toss) 도입 권고** | 만 단위 부하 + 실 토스 사양 학습 + Node.js 컨테이너 자원 격리 |
| distributed | 4 유지 + timeout/cascade 시나리오 강화 | 분산 환경 결제 cascade는 실 HTTP 통신 검증 필요 |

### 부하 테스트 시 mock 분리 권고

Stage 1 현재 in-process mock은 같은 JVM에서 callback 발사. 만 단위 동시 callback 시 우리 서버 부담이 PG mock 처리 + 비즈니스 로직 합산되어 metric 오염.

부하 테스트 진입 시:

```
[ticketing 서버 :8080]  ──HTTP──>  [Mock PG 컨테이너 :8081 (WireMock 또는 자체 Spring)]
       ▲                                  │
       │                                  │ 1초 지연 후
       └────── POST /payments/callback ───┘
```

자원 격리. 우리 서버 부하만 깨끗하게 측정. Stage 3 (queue) 또는 Stage 4 (distributed) 진입 시 도입.

## 부하 vs 형식 검증 매트릭스

| 목적 | 도구 | 규모 | 비고 |
|---|---|---|---|
| 동시성 race 재현 | In-process MockPaymentGateway | 100~1,000 동시 | 본 Lab 현재 |
| 만 단위 부하 | 별도 mock 컨테이너 (WireMock / stripe-mock / 자체) | 10K+ TPS | 우리 자원까지 무제한 |
| 실 PG 응답 형식 검증 | 토스/포트원/Stripe **test mode 키** + 테스트 카드 | 수십 건 단발 | PG ToS 준수 |
| 운영급 부하 검증 | PG와 사전 협의 (dedicated test window) | 운영급 | 가맹 계약 + 별도 SLA |

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

면접관 "만 단위 부하 테스트는 PG test 서버에 그냥 쏘면 안 되나요?":

1. "안 됩니다. 토스/포트원/Stripe 모두 test 환경에 부하 테스트 시도하면 약관 위반 + 키 정지입니다"
2. "test 환경도 운영 인프라 공유라 자사 SLA 보호 + 다른 가맹점과 공용이기 때문입니다"
3. "그래서 본 Lab은 자체 mock으로 만 단위 부하를 측정합니다. 실 PG는 응답 형식·서명·idempotency 사양 검증용으로만 단발 호출합니다"
4. "운영급 부하 검증이 필요하면 PG와 별도 협의 (dedicated test window)로 진행합니다"

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
