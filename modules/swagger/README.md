# swagger

Swagger UI / OpenAPI 자동 설정 — 문서 정보, JWT 보안 스킴, 공통 에러 응답, 스크롤을 줄이는 UI 기본값.
의존성만 추가하면 `/swagger-ui.html` 이 뜬다 (springdoc 2.8.17).

## 의존성
```groovy
implementation 'com.corelib:swagger:0.2.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
core:
  swagger:
    title: 주문 API            # 기본 "API"
    description: 주문 서비스
    version: v1                # 기본 "v1"
    error-responses:
      enabled: true            # 공통 에러 응답 — 한 줄로 켜고 끈다
      codes: [400, 404, 500]   # 모든 API 에
      secured-codes: [401, 403]  # @SecurityRequirement 가 붙은 API 에만
```
core 가 넣는 UI 기본값 (yml 에 같은 키를 적으면 그쪽이 이긴다):
`springdoc.swagger-ui.doc-expansion=none`(API 목록 접힘), `springdoc.swagger-ui.default-models-expand-depth=-1`(Schemas 숨김).

## 사용법
```java
@Tag(name = "주문")
@RestController
public class OrderController {

    @Operation(summary = "주문 목록")                                    // 공개 API → 400·404·500 자동
    @GetMapping("/api/orders/list")
    public ApiResponse<List<OrderDto>> list() { ... }

    @Operation(summary = "주문 생성")
    @SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)   // 자물쇠 → 401·403 추가
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "재고 부족 (ORDER_STOCK)")  // 직접 적은 건 유지
    @PostMapping("/api/orders/create")
    public ApiResponse<OrderDto> create(@RequestBody OrderRequest request) { ... }
}
```
- swagger 의 `@ApiResponse` 어노테이션은 common 의 `ApiResponse` 와 이름이 같다 — 어노테이션 쪽을 패키지까지 적는다.

## 자동으로 등록되는 것
| 항목 | 내용 |
|---|---|
| `OpenAPI` 빈 | 제목·설명·버전 + 보안 스킴 `bearerAuth`(HTTP bearer, JWT). 직접 `OpenAPI` 빈을 만들면 core 것은 물러난다 |
| 공통 에러 응답 | 예시 본문은 전역 핸들러 응답과 같은 `{"success":false,"data":null,"code":"C404","message":"..."}`, 스키마 `CoreErrorResponse` |
| 기동 안내 | `Swagger : http://localhost:8080/swagger-ui.html` (`springdoc.swagger-ui.enabled=false` 면 안 찍음) |

## 주의
- 운영에서 문서를 숨기려면 `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`.
- `web` 모듈의 Security 를 쓰면 Swagger 경로(`/swagger-ui/**`, `/v3/api-docs/**`)는 기본 공개다.

## 예제
[`example/swagger`](../../example/swagger) — 공개 API·자물쇠 API, 공통 에러 응답 켬/끔
