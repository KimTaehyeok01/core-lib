# mail

메일 발송 — Spring 표준 `spring.mail.*` 로 SMTP 를 잡고, 발신자는 `core.mail.from`, 발송은 `MailUtils` 한 줄.

## 의존성
```groovy
implementation 'com.corelib:mail:0.1.0'   // 저장소 설정은 루트 README "설치"
```
`spring-boot-starter-mail`·`spring-boot-starter-validation` 이 함께 들어온다.

## 설정
```yaml
spring:
  mail:
    host: smtp.gmail.com           # 없으면 MailUtils 가 초기화되지 않는다 (호출 시 '미초기화')
    port: 587
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}     # Gmail 은 앱 비밀번호
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
      mail.smtp.connectiontimeout: 5000
      mail.smtp.timeout: 5000
      mail.smtp.writetimeout: 5000
core:
  mail:
    from: noreply@my.domain
```
로컬 테스트는 Mailpit — `docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit` 후 `host: localhost`, `port: 1025`, 받은 메일은 http://localhost:8025.

## 사용법
```java
MailUtils.sendText("user@example.com", "가입 안내", "환영합니다");
MailUtils.sendHtml("user@example.com", "인증코드", "<b>123456</b>");

MailUtils.send(MailDto.SendRequest.builder()
    .to(List.of("a@example.com", "b@example.com"))
    .cc(List.of("manager@example.com"))
    .subject("월간 리포트")
    .content("<h1>10월</h1>")
    .contentType(MailDto.ContentType.HTML)
    .attachments(List.of(MailDto.Attachment.builder().fileName("report.pdf").content(bytes).contentType("application/pdf").build()))
    .build());
```

## API 요약
| 클래스 | 내용 |
|---|---|
| `MailUtils` | `sendText(to, subject, text)` · `sendHtml(to, subject, html)` · `send(SendRequest)` |
| `MailDto.SendRequest` | `from`(비우면 core.mail.from) · `to` · `cc` · `bcc` · `subject` · `content` · `contentType`(TEXT·HTML) · `attachments` / `text(...)` · `html(...)` |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| C400 | 400 | 받는 사람·제목 누락, 주소 형식 오류, 발신자 미지정 |
| MAIL-SEND | 502 | SMTP 오류 |

## 주의
- 발송은 호출한 스레드에서 동기로 한다 — 대량·재시도는 소비 앱이 큐·`@Async` 로 감싼다.
- 받는 사람 주소는 개인정보 — 로그·응답에는 `MaskingUtils.email` 로.
- 비밀번호는 환경변수로만.

## 예제
[`example/mail`](../../example/mail) — 이메일 인증코드 발송·확인 (재발송 대기·만료·5회 오입력 폐기)
