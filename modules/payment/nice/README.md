# payment/nice

나이스페이먼츠 결제 (결제창 `AUTHNICE.requestPay` + Server 승인) — 인증결과 서명 검증, 승인, 자동 망취소, 취소, 조회.

## 의존성
```groovy
implementation 'com.corelib:payment-nice:0.1.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
payment:
  nice:
    client-key: ${NICEPAY_CLIENT_KEY}   # 없으면 자동설정이 꺼진다 (호출 시 '미초기화'). 결제창 clientId 로도 쓴다
    secret-key: ${NICEPAY_SECRET_KEY}   # 서버 전용
    base-url: https://sandbox-api.nicepay.co.kr   # 샌드박스. 운영은 생략(https://api.nicepay.co.kr)
    connect-timeout: 5s
    read-timeout: 30s
```
- 키는 [나이스페이 개발자센터](https://start.nicepay.co.kr) 가입 → 샌드박스 키.

## 사용법
```java
// returnUrl 컨트롤러 — 결제창이 form POST 로 인증결과를 보낸다
@PostMapping("/nicepay/return")
public ResponseEntity<Void> returnUrl(@ModelAttribute NicePayDto.AuthResult authResult) {
    Order order = orderRepository.find(authResult.getOrderId());
    String tid = NicePayUtils.verifyAuthResult(authResult, order.getOrderId(), order.getAmount());   // 결과코드·키·주문·금액·서명
    NicePayDto.Payment payment = NicePayUtils.approve(NicePayDto.ApproveRequest.builder()
        .tid(tid).orderId(order.getOrderId()).amount(order.getAmount()).build());
    ...
}

NicePayUtils.cancel(tid, NicePayDto.CancelRequest.builder().reason("고객 변심").orderId(orderId).cancelAmt(5000).build());
NicePayUtils.getPayment(tid);
```

## 동작 원칙
- 인증결과(브라우저 경유)는 서명 `sha256(authToken + clientId + amount + secretKey)` 와 **서버 보관 주문번호·금액**을 대조해야만 승인한다.
- 승인 응답도 서명·주문번호·금액을 대조한다 — 다르면 `NICEPAY-SIGNATURE`·`ORDER`·`AMOUNT` (승인 성립 가능, 취소 필요).
- 승인이 결과불명이면 매뉴얼대로 **자동 망취소** → 성공하면 `NICEPAY-NET-CANCELLED`(결제 미성립 확정), 망취소도 실패하면 `NICEPAY-UNKNOWN`.
- 취소는 결과불명이어도 자동 재시도하지 않는다. NICEPAY 결과코드는 `NICEPAY_{code}` 로 넘긴다.

## API 요약
| 메서드 | 반환 |
|---|---|
| `verifyAuthResult(AuthResult, expectedOrderId, expectedAmount)` | `tid` |
| `approve(ApproveRequest{tid, orderId, amount})` | `Payment` |
| `netCancel(orderId)` | `Payment` — 승인 응답을 못 받았을 때 (요청 후 1시간 이내) |
| `cancel(tid, CancelRequest{reason, orderId, cancelAmt, taxFreeAmt, refund*})` | `Payment` (`balanceAmt`) |
| `getPayment(tid)` | `Payment` — `isPaid()` · `isVbankReady()` · `getPaymentStatus()` |

## 에러코드
| 코드 | 상태 | 언제 / 할 일 |
|---|---|---|
| `NICEPAY_{code}` | 400 | 인증 실패·사용자 취소·승인 거절 |
| NICEPAY-AUTH-INVALID · NICEPAY-AUTH-MISMATCH | 400 | 인증결과 필수값 없음·주문과 다름 — 승인 안 함 |
| NICEPAY-SIGNATURE | 400 | 위변조 의심 — 승인 응답이면 **즉시 취소** |
| NICEPAY-ORDER · NICEPAY-AMOUNT | 400 | 승인 응답이 주문과 다름 — **즉시 취소** |
| NICEPAY-NET-CANCELLED | 400 | 결과불명 → 망취소 완료, 결제 미성립 |
| NICEPAY-EXTERNAL | 502 | 연결 실패 |
| NICEPAY-UNKNOWN | 502 | 결과 확인 불가 — 관리자 확인 |

## 주의
- returnUrl 은 외부에서 오는 POST 다 — CSRF 필터를 쓰면 이 경로는 제외한다(서명 검증이 대신한다).
- 가상계좌는 `isVbankReady()` — 입금 확인(웹훅) 전 주문 완료 금지.

## 예제
[`example/payment/nice`](../../../example/payment/nice) — 주문 → 결제창 → returnUrl 검증·승인 → 취소, 서명 위변조·망취소 처리
