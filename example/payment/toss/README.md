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
   - 쿼리 amount 가 저장 금액과 다르면 승인하지 않음(위변조)
   - `TossPayUtils.confirm` 은 **저장 금액**으로 호출
   - 결과불명이면 모듈이 조회 API 로 재확인, 그래도 모르면 `UNKNOWN`(관리자 확인)
   - 승인됐는데 값이 다르면 즉시 자동취소
4. `/result.html` — 상태 조회, 전액/부분 취소(`POST /api/toss/orders/cancel`)

## 소비 프로젝트에 옮길 것
- `service/TossExampleService` 전체 — 주문 테이블만 DB 로 바꾸면 된다
- 시크릿 키는 서버 환경변수만, 클라이언트 키만 화면으로
- 콜백이 두 번 와도(새로고침) 두 번 승인하지 않게 상태 확인
- 가상계좌는 `WAITING_FOR_DEPOSIT` — 입금 확인(웹훅) 전 주문 완료 금지
