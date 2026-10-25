# auth/nice

NICE 통합인증 본인확인 — 휴대폰·금융인증서·아이핀·공동인증서. 토큰 발급 → 표준창 URL → 결과 조회·무결성 검증·복호화.

## 의존성
```groovy
implementation 'com.corelib:auth-nice:0.2.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
auth:
  nice:
    client-id: ${NICE_CLIENT_ID}          # 없으면 자동설정이 꺼진다 (호출 시 '미초기화')
    client-secret: ${NICE_CLIENT_SECRET}
    return-url: https://my.domain/nice/return   # NICE 에 등록한 도메인
    svc-types: [M]                        # M 휴대폰 · F 금융인증서 · I 아이핀 · U 공동인증서 (계약된 수단만)
    method-type: GET                      # 세션 쿠키(SameSite=Lax)가 붙도록 GET 권장
    base-url: https://auth.niceid.co.kr   # 기본값
    tx-ttl: 10m                           # 시작~결과 유효시간 (최대 10분)
```
- **테스트 환경이 없다** — NICE 와 계약 후 서버 출구 IP 를 등록하면 메일로 `client_id`/`client_secret` 을 받는다.
- `client-id` 가 있는데 `client-secret`·`return-url` 이 비었거나, `base-url` 이 https 가 아니거나(로컬 http 만 허용), `tx-ttl` 이 0 이하·10분 초과면 **기동이 실패한다**. 메시지에는 키 이름만 나온다 (값 없음).

## 사용법
```java
// 1. 시작 — 표준창 URL 을 화면에, txNo 는 서버 세션에만
NiceAuthDto.VerificationResponse start = NiceAuthUtils.verification(null);   // 요청값 비우면 설정값 사용
session.setAttribute("NICE_TX_NO", start.getTxNo());
return start.getAuthUrl();                                                   // 브라우저가 window.open

// 2. return_url (?web_transaction_id=...)
NiceAuthDto.VerificationConfirmResponse person = NiceAuthUtils.verificationConfirm(
    NiceAuthDto.VerificationConfirmRequest.builder().txNo(txNo).webTransactionId(webTransactionId).build());
// person.getCi() 로 회원 조회·가입 — CI 는 해시(HashUtils)로 저장, 화면에는 MaskingUtils
```

## 동작 원칙
- 서버 저장 없이 두 단계 사이 상태를 **봉인된 txNo**(AES-GCM)로 넘긴다 — 10분 유효, 위변조 시 `NICE-TX-INVALID`.
- 결과는 가이드 규칙(PBKDF2 키 유도 → AES 복호화 → HMAC 무결성 검증)으로 확인한다.
- 이름·CI·DI·휴대폰번호는 로그·예외 메시지에 남기지 않는다 (`toString` 도 가린다).
- NICE 결과코드는 `NICE_{code}` 로 넘긴다.

## API 요약
| 메서드 | 반환 |
|---|---|
| `verification(VerificationRequest{requestNo, returnUrl, closeUrl})` (null 가능) | `VerificationResponse` — `authUrl` · `txNo` |
| `verificationConfirm(VerificationConfirmRequest{txNo, webTransactionId})` | `VerificationConfirmResponse` — `name` · `birthdate`(yyyyMMdd) · `gender`(0 여·1 남) · `nationalInfo`(0 내·1 외) · `ci` · `di` · `mobileCo` · `mobileNo` |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| `NICE_{code}` | 4xx | NICE 가 거절 |
| NICE-TX-INVALID · NICE-TX-EXPIRED | 400 | txNo 위변조·만료 — 다시 인증 |
| NICE-INTEGRITY | 401 | 결과 무결성 검증 실패 |
| NICE-DECRYPT · NICE-RESULT-INCOMPLETE | 500 | 결과 해석 실패·필수 항목 없음 |
| NICE-EXTERNAL | 502 | 연결 실패 |

## 예제
[`example/auth/nice`](../../../example/auth/nice) — 본인인증 → CI 로 회원 가입/로그인, 만 14세 미만 차단
