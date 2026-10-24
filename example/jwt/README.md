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
| 로그인 | 비밀번호 확인 → `JwtUtils.issue` → 리프레시 jti 를 회원별 Set 에 추가 (기기마다 1개 — 여러 기기 동시 로그인). 이메일 없음·비밀번호 틀림은 같은 응답 |
| 로그인 제한 | 이메일당 5번 틀리면 5분 잠금(`429 LOGIN_LOCKED`, 잠금 중엔 비밀번호 계산 안 함). 없는 이메일도 더미 salt 로 같은 PBKDF2 를 계산하고 똑같이 잠근다 |
| API 호출 | `Authorization: Bearer {액세스}` — jwt 모듈 필터가 검증, 컨트롤러는 `@AuthenticationPrincipal JwtDto.Claims` |
| 갱신(회전) | `JwtUtils.verifyRefreshToken` → 옛 jti 를 지우는 데 성공한 요청만 새 jti 등록 (CAS) — 같은 리프레시가 동시에 와도 1건만 성공 |
| 재사용 감지 | 이미 쓴 리프레시가 또 오면(동시 요청의 패자 포함) 탈취로 보고 그 회원의 리프레시 전부(모든 기기) 폐기 → 다시 로그인 |
| 로그아웃 | 그 회원의 리프레시 jti 전부(모든 기기) 폐기 (액세스는 만료까지 유효 — 30분) |
| 관리자 | `/api/admin/**` 는 `CoreSecurityCustomizer` 로 `hasRole("ADMIN")` |

## 소비 프로젝트에 옮길 것
- `service/AuthExampleService` — 회원은 테이블, 리프레시 jti 는 Redis(TTL = 리프레시 유효시간)로. 회전은 "옛 jti 삭제가 1건일 때만 발급"(DB 조건부 DELETE·Redis 원자 연산)으로 같은 원자성을 지킨다
- `JwtExampleApplication.adminOnly` — 경로별 권한 규칙
- 키는 `JWT_PRIVATE_KEY` 환경변수·KMS — 임시 키 생성 코드는 옮기지 않는다
