# example-payment-toss

`modules/payment/toss` 사용 예제 — 토스페이먼츠 결제창(SDK v2) + 서버 승인·취소.

## 실행
[토스 개발자센터](https://developers.tosspayments.com) 가입 → 테스트 상점의 **API 개별 연동 키**(`test_ck_` / `test_sk_`)를 받는다.
```bash
export TOSS_CLIENT_KEY=test_ck_...
export TOSS_SECRET_KEY=test_sk_...
./gradlew :example:payment:toss:bootRun
```
화면: http://localhost:8110 — 테스트 키라 실제 돈은 나가지 않는다.

## 흐름
1. `POST /api/toss/orders/create` — **서버가 금액을 정해 저장**, orderId 발급
2. 화면이 `TossPayments(clientKey).payment(...).requestPayment(...)`
3. 토스 → `GET /toss/success?paymentKey&orderId&amount`
   - 쿼리 amount 가 저장 금액과 다르면 승인하지 않음(위변조) — 서명 없는 값이라 주문은 `READY` 로 남김
   - 파라미터 누락·형식 오류도 `/fail.html` 로 리다이렉트
   - `READY → PROCESSING` 을 원자 선점한 요청만 `TossPayUtils.confirm` 호출 — **저장 금액**으로
   - 결과불명이면 모듈이 조회 API 로 재확인, 그래도 모르면 `UNKNOWN`(관리자 확인)
   - 승인됐는데 값이 다르면 즉시 자동취소(`FAILED`)
   - 토스 거절(4xx 등 결과확정 실패)은 콜백에 서명이 없어 가짜 paymentKey 일 수 있으므로 `READY` 로 되돌림 — 화면은 고정 문구
   - 다시 온 콜백은 `FAILED` 면 `/fail.html`, 결제가 진행·성립된 상태(`PROCESSING`·`PAID`·취소 계열·`UNKNOWN` 등)는 `/result.html`
4. `/result.html` — 상태 조회, 전액/부분 취소(`POST /api/toss/orders/cancel`)
   - `CANCELING` 을 선점한 요청만 취소 호출, 취소 결과불명은 `CANCEL_UNKNOWN` — 재취소 금지(관리자 확인)
5. failUrl(`/toss/fail`) — 서명 없는 브라우저 입력이라 주문 상태를 바꾸지 않고 서버 고정 문구만 보여줌

## 소비 프로젝트에 옮길 것
- `service/TossExampleService` 전체 — 주문 테이블만 DB 로 바꾸면 된다
- 메모리 Map 의 `replace(기대값, 새값)` 은 DB 조건부 UPDATE(`WHERE status = ?`) 로 — 갱신 0건이면 다른 요청이 선점한 것
- 시크릿 키는 서버 환경변수만, 클라이언트 키만 화면으로
- 콜백이 두 번 와도(새로고침·동시 요청) 선점한 한 요청만 승인
- 가상계좌는 `WAITING_FOR_DEPOSIT` — 입금 확인(웹훅) 전 주문 완료 금지

## 예제 한계 (그대로 복사 금지)
- **인증 없음** — 상세·취소 API 가 orderId 만으로 동작한다. 실서비스는 주문 소유자/관리자 확인 필수
- **가상계좌 입금 웹훅 미구현** — 입금돼도 `WAITING_FOR_DEPOSIT` 에 머문다. 실서비스는 토스 웹훅 수신 후 `TossPayUtils.getPayment` 로 재확인해 상태를 바꾼다
- `PROCESSING` 중 서버가 죽으면 그 상태에 남는다 — 실서비스는 조회 API 로 정리하는 배치가 필요하다
