# example-jwt

`modules/jwt` + `modules/web` 로 만든 **회원 로그인** 예제 — 토큰 발급·리프레시 회전·로그아웃·관리자 API·JWKS.

## 실행
```bash
./gradlew :example:jwt:bootRun
```
`JWT_PRIVATE_KEY` 가 없으면 기동할 때 임시 RSA 키를 만든다 (재시작하면 이전 토큰은 무효). 관리자 계정이 필요하면 `ADMIN_EMAIL`, `ADMIN_PASSWORD`.

```bash
curl -X POST localhost:8114/api/auth/signup -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"pass-word-1","nickname":"길동"}'
curl -X POST localhost:8114/api/auth/login  -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"pass-word-1"}'
curl localhost:8114/api/members/me -H 'Authorization: Bearer {accessToken}'
curl -X POST localhost:8114/api/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"{refreshToken}"}'
curl localhost:8114/.well-known/jwks.json
```

## 흐름 (`service/AuthExampleService`)
| 단계 | 내용 |
|---|---|
| 가입 | 비밀번호 PBKDF2 + salt (`KdfUtils`) |
| 로그인 | 비밀번호 확인 → `JwtUtils.issue` → 리프레시 jti 를 회원별로 저장. 이메일 없음·비밀번호 틀림은 같은 응답 |
| API 호출 | `Authorization: Bearer {액세스}` — jwt 모듈 필터가 검증, 컨트롤러는 `@AuthenticationPrincipal JwtDto.Claims` |
| 갱신(회전) | `JwtUtils.verifyRefreshToken` → 저장된 jti 와 같을 때만 새 쌍, 옛 것은 무효 |
| 재사용 감지 | 이미 쓴 리프레시가 또 오면 탈취로 보고 그 회원의 리프레시 전부 폐기 → 다시 로그인 |
| 로그아웃 | 리프레시 jti 폐기 (액세스는 만료까지 유효 — 30분) |
| 관리자 | `/api/admin/**` 는 `CoreSecurityCustomizer` 로 `hasRole("ADMIN")` |

## 소비 프로젝트에 옮길 것
- `service/AuthExampleService` — 회원은 테이블, 리프레시 jti 는 Redis(TTL = 리프레시 유효시간)로
- `JwtExampleApplication.adminOnly` — 경로별 권한 규칙
- 키는 `JWT_PRIVATE_KEY` 환경변수·KMS — 임시 키 생성 코드는 옮기지 않는다
