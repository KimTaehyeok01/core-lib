# example-auth-otp

`modules/auth/otp` 로 만든 **2단계 인증** 예제 — 등록(QR) → 활성화 → 로그인 검증 → 해제.

## 실행
```bash
export OTP_SECRET_KEY=...   # OTP 비밀키 암호화 키 (base64 32byte) — 비우면 임시 키
./gradlew :example:auth:otp:bootRun
```
화면: http://localhost:8115 — Google Authenticator 등으로 QR 을 찍고 6자리 코드를 넣는다.

## 흐름 (`service/OtpExampleService`)
| 단계 | 내용 |
|---|---|
| setup | `OtpUtils.register` — 비밀키는 AES-GCM 으로 암호화해 "등록 중"으로 저장, QR 용 URI 반환. 이미 켜져 있으면 거절 |
| activate | 앱 코드가 맞으면 사용 시작 (QR 을 제대로 찍었는지 확인) |
| verify | 로그인 2단계 — 지난번 성공한 timeStep 을 넘겨 **같은 코드 재사용 차단**, 연속 실패 5회면 5분 잠금(423) |
| disable | 현재 코드가 맞아야 해제 |

## 소비 프로젝트에 옮길 것
- `service/OtpExampleService` — 상태는 회원 테이블 컬럼(암호화된 비밀키·활성화 여부·마지막 timeStep·실패 횟수·잠금 시각)
- 예제는 memberId 를 요청으로 받는다 — 실서비스는 로그인한 회원(1단계 통과) 기준으로
