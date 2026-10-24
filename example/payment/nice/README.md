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
   - `NicePayUtils.verifyAuthResult(인증결과, 저장 주문번호, 저장 금액)` — 결과코드·키·주문·금액·서명 검증.
     실패(인증 실패·사용자 취소·위변조)는 서명 검증 전 입력이라 주문을 `READY` 로 두고, 화면 문구는 서버 고정 문구
   - `READY → PROCESSING` 을 원자적으로 선점한 콜백만 승인 — 동시·중복 콜백은 승인·망취소를 호출하지 않는다
   - `NicePayUtils.approve(tid, 주문번호, 저장 금액)` — 결과불명이면 모듈이 망취소(`NET_CANCELLED`), 망취소도 실패면 `UNKNOWN`
   - 승인 응답 서명·주문·금액이 다르면 즉시 `NicePayUtils.cancel`
   - 파라미터 누락·형식 오류는 `/fail.html`. 재진입 콜백은 `FAILED` 만 `/fail.html`, 결제가 진행·성립된 그 외 상태(승인 중·완료·취소·결과불명)는
     승인 호출 없이 `/result.html` — 현재 상태를 보여 주고 새로고침하면 확정 상태가 보인다
4. `/result.html` — 상태 조회, 전액/부분 취소(`POST /api/nicepay/orders/cancel`)
   - `CANCELING` 을 선점한 요청만 취소 호출, 취소 orderId 는 회차마다 `{orderId}-c{회차}` (같은 orderId 재호출 불가)
   - 취소 결과불명이면 `CANCEL_UNKNOWN` — 이중 환불 위험이라 재취소를 막고 관리자 확인. 거절이면 원래 상태로 복원

## 소비 프로젝트에 옮길 것
- `service/NicePayExampleService` 전체 — 주문 테이블만 DB 로 바꾸면 된다
- 시크릿 키는 서버 환경변수만, clientId 만 화면으로
- returnUrl 은 POST 다 — CSRF 필터를 쓰면 이 경로는 제외(서명 검증이 대신한다)
- 가상계좌는 `WAITING_FOR_DEPOSIT` — 입금 확인(웹훅) 전 주문 완료 금지
- 메모리 Map 의 `replace(key, 읽은 값, 새 값)` 선점은 DB 조건부 UPDATE(`WHERE status = ?`)로 바꾼다

## 예제의 한계 (그대로 복사 금지)
- **인증 없음** — 상세·취소 API 가 orderId 만으로 동작한다. 실서비스는 주문 소유자/관리자 확인 필수
- **가상계좌 입금 웹훅 미구현** — 입금돼도 `WAITING_FOR_DEPOSIT` 에 머문다. 실서비스는 입금 통보 웹훅을 받아 `getPayment` 로 확인 후 전이
