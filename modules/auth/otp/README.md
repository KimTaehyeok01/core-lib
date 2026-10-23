# auth/otp

2단계 인증용 TOTP (RFC 6238) — Google Authenticator·Microsoft Authenticator 등 OTP 앱과 호환.
외부 라이브러리·계약 없이 JDK 만으로 동작하고, RFC 6238 공식 테스트 값으로 검증했다.

## 의존성
```groovy
implementation 'com.corelib:auth-otp:0.1.0'   // 저장소 설정은 루트 README "설치"
```

## 설정 (전부 기본값 있음)
```yaml
auth:
  otp:
    issuer: 내 서비스     # OTP 앱에 보이는 이름
    algorithm: SHA1       # SHA1(기본) · SHA256 · SHA512 — Google Authenticator 는 SHA1 만 확실
    digits: 6             # 6~8
    period: 30s
    window: 1             # 앞뒤 허용 단계 — 1 이면 ±30초 (폰 시계 오차)
```

## 사용법
```java
// 1. 등록 — 비밀키는 암호화(AesUtils)해서 저장, otpauthUri 로 화면이 QR 을 그린다
OtpDto.Registration registration = OtpUtils.register("hong@example.com");

// 2. 활성화·로그인 검증 — 지난번 성공한 timeStep 을 넘겨 같은 코드 재사용을 막는다
OtpDto.VerifyResult result = OtpUtils.verify(secret, code, member.getLastUsedTimeStep());
if (result.isValid()) {
    member.setLastUsedTimeStep(result.getTimeStep());
}
```

## API 요약
| 메서드 | 내용 |
|---|---|
| `register(accountName)` | `Registration` — `secret`(Base32 32자) · `otpauthUri` |
| `generateSecret()` | 20byte 난수 → Base32 |
| `otpauthUri(accountName, secret)` | `otpauth://totp/{issuer}:{account}?secret=...&issuer=...&algorithm=...&digits=...&period=...` |
| `verify(secret, code)` · `verify(secret, code, lastUsedTimeStep)` | `VerifyResult` — `isValid` · `timeStep`(실패면 -1) |
| `generateCode(secret, instant)` | 테스트·서버 간 검증용 |

## 주의
- **무차별 대입 제한은 호출측이 한다** — 6자리는 100만 가지다. 연속 실패 잠금(예: 5회 → 5분)을 꼭 둔다 (예제 참고).
- 비밀키는 비밀번호와 같다 — DB 에 평문으로 두지 말고 암호화한다. QR·수동 입력 키는 등록 화면에서만 보여 준다.
- 해제(끄기)도 현재 코드를 받아야 한다 — 세션만 훔쳐서 2단계 인증을 끄지 못하게.
- 코드 비교는 상수 시간이다.

## 예제
[`example/auth/otp`](../../../example/auth/otp) — 등록(QR) → 활성화 → 로그인 검증, 재사용 차단, 연속 실패 잠금, 비밀키 AES 암호화 저장
