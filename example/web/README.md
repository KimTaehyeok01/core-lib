# example-web

`modules/web` 로 만든 **고객 문의 접수** 예제 — 공개 접수 API + 관리자 API.

## 실행
```bash
export ADMIN_API_KEY=아무-긴-문자열      # 관리자 API 키 (비우면 관리자 API 는 항상 401)
./gradlew :example:web:bootRun
```
- 화면: http://localhost:8113 — 문의하기, 관리자 키 입력 후 목록
- 헬스체크: http://localhost:8113/actuator/health

## web 모듈을 어디에 썼나
| 기능 | 이 예제에서 | 설정 |
|---|---|---|
| 클라이언트 IP | `ClientIpUtils.resolve(request)` → IP 별 1분 3건 제한, 문의에 IP 기록 | `core.web.client-ip.trusted-proxies` |
| traceId | 접수 응답의 "추적번호" = 서버 로그의 `[traceId]` — 고객이 알려 주면 로그를 바로 찾는다 | `core.web.trace.header` |
| 비동기 MDC | `InquiryNotificationService` 의 `@Async` 알림 로그에도 같은 traceId | (자동) |
| 요청 로그 | `POST /api/inquiries/create 200 12ms ip=...` + 본문(이메일 마스킹) | `core.web.request-log.*` |
| Security | 공개 경로(`permit-paths`) 외에는 401 JSON. `CoreSecurityCustomizer` 로 `/api/admin/**` 를 ADMIN 전용 + `X-Admin-Key` 인증 | `core.web.security.*` |
| 헬스체크 | `/actuator/health` 공개, 상세 숨김 | (자동) |

## 소비 프로젝트에 옮길 것
- `service/InquiryExampleService` — 문의 테이블은 DB, IP 별 제한은 Redis 로 바꾸면 된다
- `WebExampleApplication.adminSecurity` 자리 — 실서비스는 `X-Admin-Key` 대신 jwt 인증 필터를 끼운다
- 의존성: `web` 모듈 + `spring-boot-starter-security` (Security 를 안 쓰면 빼도 IP·traceId·로그·헬스체크는 동작)
