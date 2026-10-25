# payment/toss

토스페이먼츠 결제 승인·취소·조회 (결제창 SDK v2 + 서버 승인 API).
결제창은 프론트가 띄우고, 이 모듈은 successUrl 이후의 **서버 승인**부터 맡는다.

## 의존성
```groovy
implementation 'com.corelib:payment-toss:0.2.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
payment:
  toss:
    secret-key: ${TOSS_SECRET_KEY}   # test_sk_... / live_sk_... — 없으면 자동설정이 꺼진다 (호출 시 '미초기화')
    base-url: https://api.tosspayments.com   # 기본값
    connect-timeout: 5s                       # 기본값
    read-timeout: 30s                         # 기본값
```
- 키는 [토스 개발자센터](https://developers.tosspayments.com) 가입 → 테스트 상점의 "API 개별 연동 키". 클라이언트 키(`test_ck_`)는 화면용이라 이 모듈 설정이 아니다.

## 사용법
```java
// successUrl 컨트롤러 (?paymentKey&orderId&amount) — amount 는 브라우저를 거친 값이라 서버가 보관한 금액과 비교만 한다
TossPayDto.Payment payment = TossPayUtils.confirm(TossPayDto.ConfirmRequest.builder()
    .paymentKey(paymentKey).orderId(orderId).amount(savedOrder.getAmount())    // 서버 보관 금액으로 승인
    .build());
if (payment.isWaitingForDeposit()) { ... }   // 가상계좌 — 입금 전, 주문 완료 금지

TossPayUtils.cancel(payment.getPaymentKey(), TossPayDto.CancelRequest.builder()
    .cancelReason("고객 변심").cancelAmount(5000).build());   // cancelAmount 비우면 전액

TossPayUtils.getPayment(paymentKey);   // 조회
```

## 동작 원칙
- **무상태** — 기대 주문번호·금액은 호출측이 보관한다. 승인 응답의 주문번호·금액이 다르면 `TOSS-ORDER`·`TOSS-AMOUNT` (승인이 성립했을 수 있어 취소가 필요).
- 승인이 결과불명(timeout·5xx)이면 **조회 API 로 실제 상태를 재확인**한다 — 승인됐으면 결과를 돌려주고, 미성립이면 `TOSS-NOT-APPROVED`, 조회도 안 되면 `TOSS-UNKNOWN`.
- 취소는 결과불명이어도 자동 재시도하지 않는다 (이중 취소 위험).
- 토스 4xx 오류코드는 `TOSS_{code}` 로 그대로 넘긴다 (예: `TOSS_REJECT_CARD_COMPANY`).
- 승인·취소 금액은 1 ~ 999,999,999 — 벗어나면 토스 호출 전에 `C400`.
- 호출은 동기(`.block()`)이고 한 번에 최대 `connect-timeout + read-timeout + 5초` 걸린다 (읽기 timeout 이 read 마다 다시 걸려 끝나지 않는 응답을 막는 전체 상한, 초과는 결과불명). **`@Transactional`·DB 커넥션 점유 구간 밖에서 호출한다.**

## API 요약
| 메서드 | 반환 |
|---|---|
| `TossPayUtils.confirm(ConfirmRequest{paymentKey, orderId, amount})` | `Payment` |
| `TossPayUtils.cancel(paymentKey, CancelRequest{cancelReason, cancelAmount})` | `Payment` (`balanceAmount` = 남은 금액) |
| `TossPayUtils.getPayment(paymentKey)` | `Payment` |
| `Payment` | `paymentKey` · `orderId` · `status` · `method` · `totalAmount` · `balanceAmount` · `approvedAt` · `virtualAccount` · `isDone()` · `isWaitingForDeposit()` · `getPaymentStatus()` |

## 에러코드
| 코드 | 상태 | 언제 / 할 일 |
|---|---|---|
| `TOSS_{code}` | 4xx | 토스가 거절 — 메시지를 사용자에게 안내 |
| TOSS-ORDER · TOSS-AMOUNT | 400 | 승인 응답이 주문과 다름 — **즉시 취소** |
| TOSS-NOT-APPROVED | 400 | 재조회 결과 승인 안 됨 — 결제 실패 처리 |
| TOSS-EXTERNAL | 502 | 토스 연결 실패 |
| TOSS-UNKNOWN | 502 | 결과 확인 불가 — 실패로 단정하지 말고 관리자 확인 |

## 예제
[`example/payment/toss`](../../../example/payment/toss) — 주문 → 결제창 → successUrl 승인 → 부분·전액 취소, 금액 위변조·결과불명 처리
