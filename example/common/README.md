# example-common

`modules/common` 사용 예제 — 응답은 `ApiResponse`, 실패는 `CoreException` 을 던지기만 하면 전역 핸들러가 응답으로 바꾼다.

## 실행
```bash
./gradlew :example:common:bootRun
```
| 요청 | 결과 |
|---|---|
| `POST /api/items/create` `{"name":"키보드","price":50000,"stock":3}` | 200 `{"success":true,"data":{...}}` |
| `GET /api/items/detail?id=999` | 404 `{"success":false,"code":"C404","message":"상품을 찾을 수 없습니다."}` |
| `POST /api/items/create` `{"name":" ","price":0,"stock":1}` | 400 `C400` (ValidateUtils) |
| `POST /api/items/purchase` 재고 0 상품 | 409 `ITEM_SOLD_OUT` (도메인 에러코드) |
| `GET /api/items/unexpected-error` | 500 `C500` — 내부 메시지는 숨기고 서버 로그에만 |

## 소비 프로젝트에 옮길 것
- 컨트롤러는 try-catch 없이 `ApiResponse.success(...)` 만 반환
- 서비스에서 `ValidateUtils.validate(request)`, 실패는 `throw new CoreException(코드).addErrorLogData(...)`
- 도메인 에러코드는 `ErrorCode` 를 구현한 enum 으로 모은다 (예제의 `ErrorCode.of` 는 간단 버전)
- 의존성: `common` + `spring-boot-starter-validation`
