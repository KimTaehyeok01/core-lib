# web

서블릿 웹 앱 공통 — 클라이언트 IP 확인, traceId, 요청 로그, 비동기 MDC 전달, Security 기본 체인, 헬스체크 기본값.
의존성만 추가하면 켜지고, 전부 yml 로 바꾸거나 끌 수 있다.

## 의존성
```groovy
implementation 'com.corelib:web:0.2.0'   // 저장소 설정은 루트 README "설치"
implementation 'org.springframework.boot:spring-boot-starter-security'   // Security 기본 체인을 쓸 때만
```
- `spring-boot-starter-actuator` 가 함께 들어온다 (헬스체크).
- **Spring Boot 3.5.x 전용** — actuator 를 `3.5.16` 으로 고정해 전파한다. 다른 Boot 버전 앱은 actuator 버전이 어긋난다.
- Security 는 **소비 앱이 `spring-boot-starter-security` 를 넣었을 때만** 켜진다.

## 설정 (전부 기본값 있음)
```yaml
core:
  web:
    client-ip:
      trusted-proxies: [127.0.0.0/8, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7]
      headers: [X-Forwarded-For, X-Real-IP]
    trace:
      header: X-Request-Id
    request-log:
      enabled: true
      include-body: false          # 켜면 JSON 본문도 (마스킹·길이 제한)
      body-max-length: 2000        # 바이트 — 넘는 본문은 내용 없이 "(N bytes)" 만
      exclude-paths: [/actuator/**, /swagger-ui/**, /v3/api-docs/**, ...정적 파일]
      mask-keys: [password, pw, pwd, secret, token, key, otp, pin, code, cardNo, ci, di, phone, phoneNumber, mobile, tel, email, name, address, uri, url, ...]
    security:
      enabled: true
      default-access: AUTHENTICATED   # 또는 PERMIT_ALL
      permit-paths: []                # 인증 없이 열 경로 (/actuator/health·/error 는 기본 공개)
      public-docs: false              # true 면 Swagger UI·OpenAPI 문서(/swagger-ui/**, /v3/api-docs/**) 공개
      cors-allowed-origins: []        # 비우면 CORS 허용 안 함
      cors-allowed-methods: [GET, POST, OPTIONS]
```
core 가 넣는 기본값 (yml 에 같은 키를 적으면 그쪽이 이긴다):
`logging.pattern.level=%5p [%X{traceId:-}]`, `management.endpoints.web.exposure.include=health,info`,
`management.endpoint.health.show-details=never`, `management.endpoint.health.probes.enabled=true`.

## 기능
| 기능 | 내용 |
|---|---|
| 클라이언트 IP | `ClientIpUtils.resolve(request)` — 직접 연결한 주소가 신뢰 프록시일 때만 X-Forwarded-For 를 **오른쪽부터** 따라가 첫 외부 IP. 공인 IP 가 직접 보낸 헤더·IP 형식이 아닌 값은 무시(위조 방지, DNS 조회 안 함) |
| traceId | 요청마다 추적 ID → MDC(`traceId`)·응답 헤더 `X-Request-Id`. 앞단이 준 값은 `[A-Za-z0-9._-]{8,64}` 일 때만 이어 쓴다. `TraceIdFilter.current()` |
| 비동기 MDC | `MdcTaskDecorator` — Boot 기본 TaskExecutor(`@Async`) 작업 로그에도 같은 traceId. 끝나면 스레드 MDC 를 되돌린다 |
| 요청 로그 | 로거 `core.request` — `POST /api/orders 200 35ms ip=1.2.3.4` (5xx 는 WARN). 쿼리스트링은 안 찍는다. 본문은 `include-body` 일 때 JSON 만 — JSON 으로 읽어 키 이름이 mask-keys 로 끝나면 값을 통째로 `***`(값이 배열·객체여도, 중첩 객체 안까지). `body-max-length` 를 넘었거나 JSON 으로 읽히지 않으면 내용 없이 `(N bytes)`. 응답은 버퍼링하지 않아 다운로드·SSE 스트리밍이 그대로다 |
| Security | 세션 없음(STATELESS)·CSRF/폼로그인/HTTP Basic 끔, 공개 경로 외에는 `default-access`, 401·403 은 `ApiResponse`(C401·C403) JSON. 사용자 저장소가 없으면 Boot 임시 비밀번호를 만들지 않는다 |
| 인증 실패 사유 | 인증 필터가 request attribute `WebSecurityInitialize.AUTH_ERROR_ATTRIBUTE` 에 `ErrorCode` 를 남기면, 보호 경로의 401 은 C401 대신 그 코드 + `WWW-Authenticate: Bearer error="invalid_token"` (공개 경로는 그대로 통과) |
| 컨트롤러 권한 예외 | `@PreAuthorize` 등의 `AccessDeniedException` — 익명이면 401, 로그인 사용자면 403. `security.enabled=false` 로 체인을 직접 만든 앱에도 적용 (`SecurityExceptionHandler`) |
| 헬스체크 | `/actuator/health`(+ `/liveness`·`/readiness`) 공개, 상세 숨김, `health`·`info` 외 엔드포인트 비노출 (`/actuator/info` 는 인증 필요) |
| 기동 안내 | `Health : http://localhost:8080/actuator/health`, `[core] Web ...`, `[core] Security ...`(API 문서 공개 여부 포함) |

## 인증 필터·경로 권한 끼우기
```java
@Bean
CoreSecurityCustomizer apiSecurity(JwtAuthenticationFilter jwtFilter) {
    return http -> http
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(a -> a.requestMatchers("/api/admin/**").hasRole("ADMIN"));   // core 기본 규칙보다 먼저
}
```
- 체인을 통째로 직접 짜려면 `SecurityFilterChain` 빈을 만들면 core 기본 체인은 물러난다.
- 직접 만든 `TaskDecorator` 가 있으면 core 의 MDC 전달은 물러난다 — 그 안에서 MDC 를 복사한다.

## API 요약
| 클래스 | 내용 |
|---|---|
| `ClientIpUtils` | `resolve(request)` |
| `TraceIdFilter` | `current()` · MDC 키 `MDC_KEY = "traceId"` |
| `CoreSecurityCustomizer` | `customize(HttpSecurity)` — 확장 지점 |
| `WebSecurityInitialize` | `AUTH_ERROR_ATTRIBUTE = "com.corelib.web.AUTH_ERROR"` — 인증 필터가 실패 사유(`ErrorCode`)를 남기는 request attribute |
| `MdcTaskDecorator` | 직접 만든 Executor 에 붙일 때 `executor.setTaskDecorator(new MdcTaskDecorator())` |

## 주의
- 로드밸런서가 사설망이 아니면(예: 공인 IP 프록시) `trusted-proxies` 에 그 대역을 넣어야 실제 클라이언트 IP 가 보인다.
- `include-body` 는 운영에서 끄는 것을 권장 — 마스킹 키 목록에 없는 개인정보는 가려지지 않는다.
- 마스킹은 키 이름 끝 일치라 `goodsName`(name)·에러 응답의 `code`·`hotel`(tel) 같은 값도 가린다 (개인정보 노출보다 안전한 쪽).
- Swagger 문서는 기본 비공개 — 개발 서버에서만 `public-docs: true`. springdoc 경로를 바꿨다면(`springdoc.api-docs.path`) 그 경로를 `permit-paths` 에 직접 적는다.

## 예제
[`example/web`](../../example/web) — 고객 문의 접수 (IP 별 1분 3건 제한, 추적번호, `@Async` 알림, 관리자 API 보호)
