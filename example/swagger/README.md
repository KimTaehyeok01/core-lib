# example-swagger

`modules/swagger` 사용 예제 — 의존성만 추가하면 Swagger UI 가 뜨고, `core.swagger.*` 로 제목·버전, JWT 보안 스킴(`bearerAuth`)이 등록된다.

## 실행
```bash
./gradlew :example:swagger:bootRun
```
- UI: http://localhost:8105/swagger-ui.html
- 문서 JSON: http://localhost:8105/v3/api-docs

## 소비 프로젝트에 옮길 것
- `application.yml` 의 `core.swagger` 3줄
- 컨트롤러: `@Tag`, `@Operation`, 인증 API 에 `@SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)`
- DTO: `@Schema(description, example)`
- swagger 의 `@ApiResponse` 는 common 의 `ApiResponse` 와 이름이 같다 — 어노테이션 쪽을 패키지까지 적는다
- 운영 노출 차단: `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`
