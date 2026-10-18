# example-payment-nice

`modules/payment/nice` 사용 예제 — 나이스페이먼츠 결제창(`AUTHNICE.requestPay`) + 서버 승인·취소.

## 실행
[나이스페이 개발자센터](https://start.nicepay.co.kr) 가입 → 샌드박스 **클라이언트 키 / 시크릿 키**를 받는다.
```bash
export NICEPAY_CLIENT_KEY=...
export NICEPAY_SECRET_KEY=...
./gradlew :example:payment:nice:bootRun
```
화면: http://localhost:8111 — 샌드박스(`sandbox-api.nicepay.co.kr`)라 실제 돈은 나가지 않는다.
운영 전환 시 `NICEPAY_BASE_URL=https://api.nicepay.co.kr` + 운영 키.

## 흐름
1. `POST /api/nicepay/orders/create` — **서버가 금액을 정해 저장**, orderId 발급
2. 화면이 `AUTHNICE.requestPay({clientId, method, orderId, amount, goodsName, returnUrl})`
3. NICEPAY → `POST /nicepay/return` (form) — 서비스가
   - `NicePayUtils.verifyAuthResult(인증결과, 저장 주문번호, 저장 금액)` — 결과코드·키·주문·금액·서명 검증
   - `NicePayUtils.approve(tid, 주문번호, 저장 금액)` — 결과불명이면 모듈이 망취소(`NET_CANCELLED`), 망취소도 실패면 `UNKNOWN`
   - 승인 응답 서명·주문·금액이 다르면 즉시 `NicePayUtils.cancel`
4. `/result.html` — 상태 조회, 전액/부분 취소(`POST /api/nicepay/orders/cancel`)

## 소비 프로젝트에 옮길 것
- `service/NicePayExampleService` 전체 — 주문 테이블만 DB 로 바꾸면 된다
- 시크릿 키는 서버 환경변수만, clientId 만 화면으로
- returnUrl 은 POST 다 — CSRF 필터를 쓰면 이 경로는 제외(서명 검증이 대신한다)
- 가상계좌는 `WAITING_FOR_DEPOSIT` — 입금 확인(웹훅) 전 주문 완료 금지
