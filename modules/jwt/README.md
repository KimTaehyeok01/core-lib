# jwt

JWT(JWS) 토큰 발급·검증 — RS256(기본)·HS256, 액세스/리프레시 토큰, JWKS(공개키 목록).
`web` 모듈 + `spring-boot-starter-security` 와 같이 쓰면 **Bearer 인증 필터가 자동으로** core 보안 체인에 붙는다.

## 의존성
```groovy
implementation 'com.corelib:jwt:0.1.0'   // 저장소 설정은 루트 README "설치"
implementation 'com.corelib:web:0.1.0'                                   // Bearer 필터를 쓸 때
implementation 'org.springframework.boot:spring-boot-starter-security'   // Bearer 필터를 쓸 때
```
`nimbus-jose-jwt` 10.10 이 함께 들어온다.

## 설정
```yaml
core:
  jwt:
    algorithm: RS256                  # RS256(기본) · HS256
    private-key: ${JWT_PRIVATE_KEY}   # RS256 개인키 PEM — 없고 public-key 만 있으면 "검증 전용"
    public-key: ${JWT_PUBLIC_KEY:}    # 비우면 개인키에서 만든다
    secret: ${JWT_SECRET:}            # HS256 일 때만, UTF-8 32byte 이상
    key-id: core-1                    # 헤더 kid — 키 교체 때 구분
    issuer: my-service                # iss — 검증 때 일치해야 한다
    audience:                         # aud — 비우면 넣지도 검사하지도 않는다
    access-token-ttl: 30m
    refresh-token-ttl: 14d
    clock-skew: 30s                   # 서버 간 시계 오차 허용
    security:
      enabled: true                   # Bearer 필터 연동 (web + security 가 있을 때)
```
키 셋 중 하나도 없으면 자동설정이 꺼진다 (호출 시 "미초기화", 기동 안내에 표시).

RS256 키 만들기:
```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```
PEM 내용을 그대로 환경변수에 넣는다 (줄바꿈 포함). 파일은 레포에 올리지 않는다.

## 사용법
```java
// 로그인 성공
JwtDto.TokenPair pair = JwtUtils.issue(memberId, List.of("USER"), Map.of("nickname", nickname));
// pair.getAccessToken() · getRefreshToken() · getRefreshTokenId()(jti — 저장해서 회전·폐기에 쓴다)

// 컨트롤러 — Bearer 필터가 검증해 넣어 준다
@GetMapping("/api/members/me")
public ApiResponse<MeDto> me(@AuthenticationPrincipal JwtDto.Claims claims) {
    String memberId = claims.getSubject();
}

// 갱신 — 리프레시 토큰 검증 후 jti 가 유효한지(폐기·재사용) 호출측이 확인하고 새 쌍
JwtDto.Claims refresh = JwtUtils.verifyRefreshToken(refreshToken);

// 다른 서비스에 공개키 제공
@GetMapping("/.well-known/jwks.json")
public Map<String, Object> jwks() { return JwtUtils.jwks(); }
```

## Bearer 필터 동작
| 요청 | 결과 |
|---|---|
| `Authorization` 헤더 없음 | 그냥 통과 — 공개 경로는 그대로, 인증 경로는 core 체인이 401 `C401` |
| 유효한 액세스 토큰 | 로그인 상태. `roles` 가 `ROLE_` 권한이 된다 (`ADMIN` → `hasRole("ADMIN")`) |
| 만료 | 401 `JWT-EXPIRED` + `WWW-Authenticate: Bearer error="invalid_token"` — 클라이언트는 갱신 |
| 위조·형식 오류·다른 발급자 | 401 `JWT-INVALID` — 다시 로그인 |
| 리프레시 토큰을 넣음 | 401 `JWT-TYPE` |

## API 요약
| 메서드 | 내용 |
|---|---|
| `issue(subject, roles, extraClaims)` | 액세스 + 리프레시 `TokenPair` (리프레시에는 extra 를 싣지 않는다) |
| `issueAccessToken(subject, roles, extraClaims)` | 액세스만 |
| `verify(token)` · `verifyAccessToken(token)` · `verifyRefreshToken(token)` | `Claims` — `subject` · `tokenType` · `roles` · `tokenId` · `issuedAt` · `expiresAt` · `extra` |
| `jwks()` | `{"keys":[...]}` 공개키만 (HS256 이면 빈 목록) |
| `isIssuable()` | 발급 가능 여부 (검증 전용이면 false) |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| JWT-EXPIRED | 401 | 만료 (시계 오차 넘어서) |
| JWT-INVALID | 401 | 서명·형식·알고리즘·발급자·대상 불일치, 8KB 초과 |
| JWT-TYPE | 401 | 액세스 자리에 리프레시(또는 반대) |
| JWT-KEY | 500 | 검증 전용 설정에서 발급 시도 |

## 주의
- **무상태** — core 는 토큰을 저장하지 않는다. 로그아웃·리프레시 회전·탈취 감지는 호출측이 jti 를 저장해서 한다 (예제 참고).
- 액세스 토큰은 로그아웃해도 만료까지 유효하다 — 짧게(기본 30분) 둔다.
- 검증 때 토큰 헤더의 `alg` 가 설정과 같아야 한다 — `alg:none`, 공개키를 HMAC 비밀값으로 쓰는 바꿔치기 공격을 막는다.
- 예약 claim(`iss`·`sub`·`exp`·`roles`·`token_type` 등)은 `extraClaims` 로 덮어쓸 수 없다.

## 예제
[`example/jwt`](../../example/jwt) — 가입·로그인, 리프레시 회전(재사용 감지 시 전체 폐기), 로그아웃, 관리자 API, JWKS
