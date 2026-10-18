# example-mail

`modules/mail` 로 만든 **이메일 인증코드** 예제 — 회원가입·이메일 변경 때 주소 소유 확인.

## 실행
로컬 테스트 SMTP(Mailpit)를 띄우고 예제를 실행한다.
```bash
docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit
./gradlew :example:mail:bootRun
```
- 화면: http://localhost:8106 — 이메일 입력 → 코드 받기 → 코드 입력
- 받은 메일: http://localhost:8025

실제 SMTP(예: Gmail)는 환경변수로 바꾼다 — `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`(앱 비밀번호), `MAIL_SMTP_AUTH=true`, `MAIL_STARTTLS=true`, `MAIL_FROM`.

## 서비스 규칙 (`service/EmailVerificationExampleService`)
| 규칙 | 값 | core 유틸 |
|---|---|---|
| 코드 | 숫자 6자리, SecureRandom | `RandomStringUtils.randomNumeric` |
| 발송 | HTML 메일, 발신자 `core.mail.from` | `MailUtils.sendHtml` |
| 보관 | 코드 해시만, 비교는 상수 시간 | `HashUtils.sha256Hex`, `HmacUtils.isEqual` |
| 유효시간 / 재발송 대기 | 5분 / 60초 (`example.verification.*`) | |
| 오입력 | 5번 틀리면 코드 폐기 | |
| 화면·로그 | 이메일 마스킹 | `MaskingUtils.email` |

## 소비 프로젝트에 옮길 것
- `service/EmailVerificationExampleService` — 인증 대기 저장소만 Redis(TTL)·테이블로 바꾸면 된다
- 발송 실패면 대기 코드를 지워 바로 재요청할 수 있게
- 대량·비동기 발송(재시도 큐)은 소비 프로젝트 책임
