# example-payment-kspay

`modules/payment/kspay` 사용 예제 — KSNET 통합모듈 V1.4(PC) 결제창 + 서버 승인, webfep 취소.
화면 구조는 KSNET 자료실의 **통합모듈 V1.4 PC 공식 샘플**(`kspay_wh_order` → `kspay_wh_rcv` → `kspay_wh_result`)을 그대로 따른다.

## 실행
```bash
./gradlew :example:payment:kspay:bootRun
```
화면: http://localhost:8112 — 기본 상점아이디는 공식 샘플의 테스트 상점(`2999199999`).

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `KSPAY_MID` | `2999199999` | 상점아이디 |
| `KSPAY_API_KEY` | (없음) | webfep 취소용 pgapi 키 — KSTA > PG상점정보. 없으면 취소만 안 된다 |
| `KSPAY_WEBFEP_BASE_URL` | `https://paydev.ksnet.co.kr` | 운영: `https://pay.ksnet.co.kr` |

## 흐름
1. `POST /api/kspay/orders/create` — **서버가 금액을 정해 저장**, 주문번호 발급
2. 화면이 `KSPayWeb` form(sndStoreid·sndOrdernumber·sndAmount·sndReply…)을 채워 `_pay(form)` → 결제창
3. 결제창 → `POST /kspay/rcv`(sndReply) — 결제키(reCommConId)를 `opener.eparamSet()` → `opener.goResult()` 로 넘김
   (사용자 취소 `reCnclType=1` 이면 `opener.mcancel()`)
4. `goResult()` 가 form 을 `POST /kspay/result` 로 제출 → 서비스가 `KsPayUtils.approve(결제키, 주문번호, 저장 금액)`
   - form 의 sndAmount 는 쓰지 않는다(위변조 가능)
   - 결과불명이면 모듈이 같은 결제키로 1회 재전송
   - 승인 응답의 상점아이디·주문번호·금액이 다르면 `UNKNOWN` — KSTA 에서 확인 후 취소
5. `/result.html` — 상태 조회, 전액/부분 취소(`POST /api/kspay/orders/cancel`)
   - 결제수단·TID 는 승인 때 저장한 값으로 `KsPayUtils.cancel`
   - 부분취소는 cancelSeq 1~9 를 서비스가 센다. 휴대폰·가상계좌는 전액만(가상계좌는 미입금 회수)

## 소비 프로젝트에 옮길 것
- `service/KsPayExampleService` 전체 — 주문 테이블만 DB 로 바꾸면 된다
- `controller` 의 `/kspay/rcv` 페이지 — 결제키는 반드시 HTML 이스케이프
- 모바일 결제창은 스크립트·경로가 `KSPayMobileV1.4` 이고 `Channel.MOBILE`(주문번호 30byte)로 승인
- 가상계좌는 `WAITING_FOR_DEPOSIT` — 입금 통보 전 주문 완료 금지
