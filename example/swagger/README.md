# example-swagger

`modules/swagger` 사용 예제 — 의존성만 추가하면 Swagger UI 가 뜨고, `core.swagger.*` 로 제목·버전, JWT 보안 스킴(`bearerAuth`)이 등록된다.

## 실행
```bash
./gradlew :example:swagger:bootRun
```
- UI: http://localhost:8105/swagger-ui.html
- 문서 JSON: http://localhost:8105/v3/api-docs

## 공통 에러 응답
`application.yml` 의 `core.swagger.error-responses` 로 켜고 끈다.
- `codes`(기본 400·404·500) — 모든 API, `secured-codes`(기본 401·403) — 자물쇠 API(`@SecurityRequirement`)에만
- 컨트롤러에 직접 적은 `@ApiResponse`(예: 상품 등록의 400 설명)는 그대로 둔다
- 화면 기본값: API 목록 접힘, Schemas 숨김 — `springdoc.swagger-ui.doc-expansion` 등을 yml 에 적으면 그 값이 우선

## 소비 프로젝트에 옮길 것
- `application.yml` 의 `core.swagger` (제목·버전 + `error-responses`)
- 컨트롤러: `@Tag`, `@Operation`, 인증 API 에 `@SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)`
- DTO: `@Schema(description, example)`
- swagger 의 `@ApiResponse` 는 common 의 `ApiResponse` 와 이름이 같다 — 어노테이션 쪽을 패키지까지 적는다
- 운영 노출 차단: `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`
