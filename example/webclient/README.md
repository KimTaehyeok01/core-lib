# example-webclient

`modules/webclient` 로 만든 **택배 접수·배송 조회** 예제 — 외부 택배사 API 호출과 실패 처리.

## 실행
```bash
./gradlew :example:webclient:bootRun
```
기본 호출 대상은 이 앱 안의 **가짜 택배사**(`/fake-courier/v1/shipments`) — 조회할 때마다 배송이 한 단계씩 진행된다.
```bash
curl -X POST localhost:8104/api/shipments/register -H 'Content-Type: application/json' \
  -d '{"orderNo":"ORD-1","receiverName":"홍길동","receiverMobileNo":"010-1234-5678","address":"서울시 송파구"}'
curl 'localhost:8104/api/shipments/track?orderNo=ORD-1'
```
실제 택배사로 바꾸려면 `COURIER_BASE_URL`, `COURIER_API_KEY` 만 지정한다.

## 실패 분류별 처리 (`WebClientUtils.classify`)
| 분류 | 접수 (POST — 재전송하면 이중접수) | 조회 (GET — 여러 번 불러도 안전) |
|---|---|---|
| `REJECTED` (4xx) | 실패 확정, 택배사 코드 `COURIER_{code}` 로 전달 | 그대로 전달 |
| `NOT_SENT` (연결 실패) | 실패 확정, 다시 접수 가능 | 1회 재시도 |
| `UNKNOWN` (timeout·5xx) | **재전송 금지** → 주문번호로 조회해 확인. 없으면 실패 확정, 조회도 안 되면 `CHECK_REQUIRED`(재접수 차단) | 1회 재시도 |

## 소비 프로젝트에 옮길 것
- `service/ShipmentExampleService` — 배송 테이블만 DB 로 바꾸면 된다
- timeout 은 반드시 지정, 인증 헤더는 `defaultHeader`, 4xx 본문은 `findClientError` 로 읽는다
- `FakeCourier*` 는 로컬 실행용 가짜 외부 시스템이다 — 옮기지 않는다
