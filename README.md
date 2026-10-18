# core-lib

개인 프로젝트에서 재사용할 공통 모듈 라이브러리. 결제·본인인증·파일·메일 같은 기능을 프로젝트마다 다시 만들지 않도록,
필요한 모듈만 골라 의존성으로 붙이면 자동설정으로 바로 동작한다.

- `modules/` — 라이브러리 본체 (배포 대상)
- `example/` — 모듈 사용법 가이드. 각 모듈을 서비스 코드에서 어떻게 부르는지 보여주는 실행 가능한 예제 (배포 제외)
- JDK 17 / Spring Boot 3.5.x (Servlet·MVC) / Gradle 8.14.3 (wrapper 포함), 패키지 `com.corelib.*`

## 시작하기 — 새 프로젝트에 붙이는 3단계

1. **의존성** — 필요한 모듈만 (좌표는 아래 [모듈 좌표](#모듈-좌표-artifactid))
   ```groovy
   repositories { maven { url 'https://jitpack.io' } }
   dependencies {
       implementation 'com.github.KimTaehyeok01.core-lib:common:v0.1.0'
       implementation 'com.github.KimTaehyeok01.core-lib:payment-toss:v0.1.0'
   }
   ```
2. **yml** — 모듈별 설정(키는 `${환경변수}` 로만). 아래 [모듈별 사용법](#모듈별-사용법) 또는 `example/{모듈}/src/main/resources/application.yml`
3. **서비스에서 호출** — `TossPayUtils.confirm(...)` 처럼 static 유틸을 부른다. 흐름은 `example/{모듈}/.../service/` 를 그대로 따라 한다

앱을 실행하면 콘솔의 **기동 안내**에 붙인 모듈의 초기화 결과가 나온다 — `미초기화` 가 보이면 적힌 설정을 넣으면 된다.

## 구조

```
core-lib/
└── modules/
    ├── lang/        문자열·컬렉션·난수 문자열·개인정보 마스킹 (순수 자바, 최하단)
    ├── common/      공통 예외·에러코드·응답 포맷·전역 예외 핸들러·검증
    ├── crypto/      AES(GCM/CBC)·HMAC·SHA·Base64(url)·PBKDF2
    ├── webclient/   외부 API 호출 (WebClient + timeout + 결과확정/불명 분류)
    ├── swagger/     Swagger/OpenAPI 자동 설정
    ├── mail/        메일 발송
    ├── file/        파일 업로드/다운로드 (로컬 / S3)
    ├── auth/        본인인증
    │   ├── nice/    NICE 통합인증
    │   └── kcb/     KCB 휴대폰 본인확인 (OkCert3 팝업)
    └── payment/     결제
        ├── toss/    토스페이먼츠
        ├── nice/    나이스페이먼츠
        └── kspay/   KSNET KSPAY (승인 통합모듈 / 취소 webfep)
example/             모듈별 실행 예제 (modules/ 와 같은 모양, 배포 제외)
```

`modules`, `auth`, `payment` 는 묶는 폴더이고 그 안의 폴더가 실제 모듈이다.
의존 방향: `lang` ← `common` ← (`crypto`, `webclient`, swagger, mail, file) ← 벤더 모듈(auth/*, payment/*).

## 예제 (example/)

모듈마다 그대로 따라 만들 수 있는 Spring Boot 예제 앱이 있다. 계층은 **Controller / Service / Dto** 만 두고,
서비스가 core 유틸(`TossPayUtils`, `NiceAuthUtils` …)을 실제로 호출한다. 저장은 메모리(`ConcurrentHashMap`, 실서비스는 DB).

```bash
./gradlew :example:payment:kspay:bootRun     # 예제 하나 실행
./gradlew :example:payment:kspay:test        # 가짜 벤더 서버로 전 흐름 테스트
```

| 예제 | 포트 | 화면/핵심 | 필요한 키 |
|------|------|-----------|-----------|
| `example/lang` | 8101 | 비회원 게시판 (등록·목록·삭제 PIN) | - |
| `example/common` | 8102 | ApiResponse·CoreException·검증 400 | - |
| `example/crypto` | 8103 | 개인정보 암호화 고객 (가입·로그인) | `CRYPTO_AES_KEY`, `CRYPTO_INDEX_KEY` (없으면 임시키) |
| `example/webclient` | 8104 | 택배 접수·배송 조회 (내장 가짜 택배사) | - |
| `example/swagger` | 8105 | `/swagger-ui.html` — 공통 에러 응답 켜고 끄기 | - |
| `example/mail` | 8106 | 이메일 인증코드 발송·확인 (`index.html`) | 기본 Mailpit `localhost:1025` |
| `example/file` | 8107 | 업로드·다운로드·삭제 (`index.html`) | - (로컬 저장) |
| `example/auth/nice` | 8108 | 본인인증 → CI 로 가입/로그인 (14세 미만 차단) | `NICE_CLIENT_ID`, `NICE_CLIENT_SECRET` |
| `example/auth/kcb` | 8109 | 본인확인 → CI 로 가입/로그인 (14세 미만 차단) | `KCB_CP_CD`, 라이선스, `libs/` 에 KCB jar |
| `example/payment/toss` | 8110 | SDK v2 결제창 → successUrl 승인 → 취소 | `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY` |
| `example/payment/nice` | 8111 | `AUTHNICE.requestPay` → returnUrl 검증·승인 → 취소 | `NICEPAY_CLIENT_KEY`, `NICEPAY_SECRET_KEY` |
| `example/payment/kspay` | 8112 | 통합모듈 결제창 → rcv → 승인 → webfep 취소 | 기본 테스트 상점, 취소는 `KSPAY_API_KEY` |

각 예제 폴더의 `README.md` 에 흐름과 "소비 프로젝트에 옮길 것"이 있다.

## 설계 원칙

- core 는 **기능**만 제공하고 **비즈니스 판단**은 하지 않는다 — 도메인(주문/회원)을 모른 채 "요청을 처리하고 결과를 검증해 돌려주는" 지점까지만 책임진다.
- core 는 **상태·업무 데이터를 저장하지 않는다** — 어디에 쓰일지 모르기 때문. 주문·회원·결제 결과·인증 결과·벤더 토큰은 보관하지 않고 호출측에 돌려준다. Utils 가 들고 있는 것은 기동 시 한 번 넣는 설정·도구(WebClient, Property)뿐이다. (예외: file 모듈은 파일 저장이 역할이며 위치는 소비 앱이 정한다)
- 벤더 모듈은 `{벤더}Property`(설정·`validate()`) / `{벤더}Utils`(static 진입점) / `{벤더}Dto`(요청·응답) / `{벤더}Initialize`(자동설정) / `{벤더}StartupReport`(기동 안내 보고) 로 나눈다.
  - Initialize 는 키가 없으면 꺼진다(앱은 뜨고 호출 시 "미초기화"). StartupReport 는 조건 없이 등록돼 꺼진 경우에도 무엇이 빠졌는지 알린다.
- 자동설정은 각 모듈의 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 에 등록한다 — 소비 앱 패키지가 달라도 의존성만 붙이면 동작한다. (IntelliJ 에서 이 파일이 Spring 아이콘으로 보이는 건 유료 Spring 플러그인 표시일 뿐, 동작과 무관)
- 결제는 무상태 — 기대 주문번호·금액은 호출측이 보관하고 응답과 대조한다. 실패는 **결과확정**(거절·미전송)과 **결과불명**(timeout·5xx)으로 나눠 처리한다.
- 에러는 `CoreException` 하나로 던지고, 전역 핸들러가 `ApiResponse` 로 바꾼다. 벤더 오류코드는 `TOSS_{code}` / `NICEPAY_{code}` / `KSPAY_{code}` / `NICE_{code}` / `KCB_{code}` 로 그대로 넘긴다.
- 개인정보(이름·CI·DI·휴대폰·카드번호·이메일)는 로그·예외 메시지에 남기지 않는다.
- 코드는 공개 스펙(벤더 공식 문서·공개 샘플) 기준으로 새로 작성했다. 특정 회사의 사내 연동 코드는 포함하지 않는다.

## 모듈별 사용법

### lang / common — 기본 유틸, 응답 포맷과 예외

```java
StringUtils.isBlank(value);  CollectionUtils.isEmpty(list);  RandomStringUtils.randomNumeric(6);
MaskingUtils.name("홍길동");  MaskingUtils.mobile("01012345678");  MaskingUtils.email("user@example.com");  // 홍*동 · 010-****-5678 · us**@example.com
throw new CoreException(CommonErrorCode.NOT_FOUND).addErrorLogData("orderId", orderId);  // → 404 ApiResponse
return ApiResponse.success(data);
ValidateUtils.validate(request);                                                          // jakarta 어노테이션 검증
```
웹 앱이면 전역 예외 핸들러(`CoreExceptionHandler`)가 자동 등록된다 — `CoreException` 은 담긴 코드·상태로, Spring MVC 요청 오류(깨진 JSON·파라미터 누락·405·없는 경로)는 C400/C404/C405 로, 그 외는 내부 메시지를 숨기고 C500 으로.

**기동 안내** — 앱이 뜨면 콘솔에 노란 CORE 로고, 실행 주소(Local·Network·Swagger), 붙인 모듈의 초기화 결과가 한 번 찍힌다.
키가 없어 꺼진 모듈은 `미초기화 — payment.toss.secret-key 미설정` 처럼 무엇을 넣으면 되는지 알려준다(키·비밀번호는 찍지 않는다).
```yaml
core:
  startup-log:
    enabled: true          # false 면 출력 안 함
spring:
  output:
    ansi:
      enabled: always      # IntelliJ 일반 실행에서도 색 표시
```

### crypto

```java
byte[] key = AesUtils.generateKey(32);
byte[] sealed = AesUtils.encryptWithIv(plain, key, AesUtils.Mode.GCM);         // IV || 암호문
byte[] plain  = AesUtils.decryptWithIv(sealed, key, AesUtils.Mode.GCM, 12);
String sig = HashUtils.sha256Hex(text);   byte[] mac = HmacUtils.hmacSha256(key, message);
HmacUtils.isEqual(expected, actual);       // 서명 비교는 항상 상수 시간
```

### webclient

```java
WebClient client = WebClientUtils.builder("https://api.vendor.com", Duration.ofSeconds(5), Duration.ofSeconds(30)).build();
Result r = WebClientUtils.post(client, "/v1/items/{id}", h -> h.setBearerAuth(token), body, Result.class, id).block();
// 실패 분류: WebClientUtils.classify(e) → REJECTED(4xx) / NOT_SENT(연결 실패) / UNKNOWN(timeout·5xx)
```
- MVC 앱은 `.block()`, WebFlux 앱은 Mono 를 그대로 조합한다(이벤트 루프에서 `.block()` 금지).
- 테스트용 로컬 HTTP 서버 `MockHttpServer` 를 testFixtures 로 제공한다 — `testImplementation testFixtures(project(':modules:webclient'))`.

### payment/toss — 토스페이먼츠

```yaml
payment:
  toss:
    secret-key: ${TOSS_SECRET_KEY}     # test_sk_... / live_sk_...
```
```java
TossPayDto.Payment payment = TossPayUtils.confirm(TossPayDto.ConfirmRequest.builder()
    .paymentKey(paymentKey).orderId(orderId).amount(expectedAmount).build());
TossPayUtils.cancel(payment.getPaymentKey(), TossPayDto.CancelRequest.builder().cancelReason("고객 변심").build());
```
- 결과불명이면 조회 API 로 실제 상태를 재확인한다. 가상계좌는 `isWaitingForDeposit()`.

### payment/nice — 나이스페이먼츠 (Server 승인)

```yaml
payment:
  nice:
    client-key: ${NICEPAY_CLIENT_KEY}
    secret-key: ${NICEPAY_SECRET_KEY}
    base-url: https://sandbox-api.nicepay.co.kr   # 운영은 생략
```
```java
// returnUrl 컨트롤러 (@ModelAttribute NicePayDto.AuthResult authResult)
String tid = NicePayUtils.verifyAuthResult(authResult, expectedOrderId, expectedAmount);
NicePayDto.Payment payment = NicePayUtils.approve(NicePayDto.ApproveRequest.builder()
    .tid(tid).orderId(expectedOrderId).amount(expectedAmount).build());
```
- 승인 결과불명이면 매뉴얼 지침대로 **자동 망취소**(`NET_CANCELLED`). 망취소까지 실패하면 `RESULT_UNKNOWN`(관리자 확인).

### payment/kspay — KSNET KSPAY

```yaml
payment:
  kspay:
    mid: ${KSPAY_MID}                  # 상점아이디
    api-key: ${KSPAY_API_KEY}          # webfep pgapi 키 (KSTA > PG상점정보) — 취소에만 필요
    webfep-base-url: https://paydev.ksnet.co.kr   # 개발 서버, 운영은 생략(pay.ksnet.co.kr)
```
```java
// 결제창 콜백(reCommConId)으로 승인 — 통합모듈 web_host
KsPayDto.Approval approval = KsPayUtils.approve(KsPayDto.ApproveRequest.builder()
    .payKey(reCommConId).orderNo(expectedOrderNo).amount(expectedAmount).channel(KsPayDto.Channel.PC).build());
// 취소 — webfep (카드/계좌이체 부분취소 가능, 가상계좌는 미입금 회수)
KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
    .payMethod(approval.getPayMethod()).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey(approval.getTid()).build());
```
- 승인 응답은 상점아이디·주문번호·금액을 대조한다. 결과불명이면 같은 결제키로 1회 재전송해 결과를 다시 받는다(webfep 에 조회 API 가 없음).
- 취소 성공은 `code=A0200` **그리고** `respCode` 가 0 으로만 이뤄졌을 때(카드 `0000`, 계좌이체 `000`). A0500·A0999 는 결과불명.

### auth/nice — NICE 통합인증

```yaml
auth:
  nice:
    client-id: ${NICE_CLIENT_ID}
    client-secret: ${NICE_CLIENT_SECRET}
    return-url: https://my.domain/nice/return
    svc-types: [M]                     # M 휴대폰 / F 금융인증서 / I 아이핀 / U 공동인증서 (계약된 것만)
```
```java
NiceAuthDto.VerificationResponse start = NiceAuthUtils.verification(null);   // authUrl 을 브라우저에서 window.open
// return-url 컨트롤러: ?web_transaction_id=...
NiceAuthDto.VerificationConfirmResponse person = NiceAuthUtils.verificationConfirm(
    NiceAuthDto.VerificationConfirmRequest.builder().txNo(start.getTxNo()).webTransactionId(webTransactionId).build());
```
- 서버 저장 없이 두 단계 사이 상태를 봉인된 `txNo` 로 넘긴다(10분 유효, 1회 사용).

### auth/kcb — KCB 휴대폰 본인확인 (OkCert3 팝업)

```yaml
auth:
  kcb:
    cp-cd: ${KCB_CP_CD}                        # 회원사코드 12자리
    license-path: /secure/kcb/V..._license.dat # KCB 가 준 라이선스 파일 (저장소에 넣지 않는다)
    return-url: https://my.domain/kcb/return
    site-name: 내 서비스
    site-url: my.domain
```
```groovy
// 소비 앱: 계약 후 받은 KCB jar 를 추가
implementation files('libs/OkCert3-java1.5-2.2.3.jar')
```
```java
KcbAuthDto.StartResponse start = KcbAuthUtils.start(null);   // start.formFields() 를 start.getPopupUrl() 로 자동 POST
// return-url 컨트롤러: ?mdl_tkn=...
KcbAuthDto.ConfirmResponse person = KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn(mdlTkn).build());
```
- KCB jar 는 공개 배포되지 않아 코어는 `kcb.module.v3.OkCert` 를 리플렉션으로 호출한다. jar 가 없으면 `MODULE_NOT_FOUND`.

### file — 로컬 / S3

```yaml
core:
  file:
    storage: local                     # 또는 s3 (software.amazon.awssdk:s3 의존성 추가 필요)
    max-size: 10MB
    allowed-extensions: [png, jpg, pdf]
    s3:
      bucket: my-bucket
```
```java
FileDto.StoredFile stored = FileUtils.upload("profile", multipartFile);   // DB 에는 stored.getKey() 저장
String url = FileUtils.getUrl(stored.getKey());                           // S3 는 만료되는 presigned URL
```
- 경로 탈출(`../`)·웹셸 확장자(jsp/php 등, 대소문자·이중확장자 포함)는 항상 차단, svg/html/js 는 허용 목록에 명시해야만 허용.

### mail

```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
core:
  mail:
    from: noreply@my.domain
```
```java
MailUtils.sendText("user@example.com", "가입 안내", "환영합니다");
MailUtils.sendHtml("user@example.com", "인증코드", "<b>123456</b>");
MailUtils.send(MailDto.SendRequest...);   // 참조·숨은참조·첨부
```

### swagger

```yaml
core:
  swagger:
    title: 주문 API
    version: v1
```
JWT 보안 스킴 `bearerAuth` 가 등록된다 — 컨트롤러에 `@SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)`.

공통 에러 응답을 yml 로 켜고 끈다 — 예시 본문은 전역 핸들러와 같은 `ApiResponse`(C400·C404…).
```yaml
core:
  swagger:
    error-responses:
      enabled: true              # 한 줄로 켜고 끄기
      codes: [400, 404, 500]     # 모든 API
      secured-codes: [401, 403]  # @SecurityRequirement API 에만
```
- 컨트롤러에 직접 적은 `@ApiResponse` 코드는 덮어쓰지 않는다
- UI 기본값으로 API 목록 접힘(`doc-expansion: none`), Schemas 숨김(`default-models-expand-depth: -1`) — yml 에 같은 키를 적으면 그 값이 우선

## 주의

| 항목 | 내용 |
|------|------|
| KCB 결과 필드명 | 호출 규격(서비스명·form 필드)은 공개 샘플로 확인했지만 결과 필드명(RSLT_NAME 등)은 공개 자료로 확정하지 못했다. 계약 가이드와 다르면 `ConfirmResponse.getRaw()` 로 꺼내 쓰고 매핑을 고친다 |
| netty 버전 | webclient 모듈이 netty 를 4.1.139.Final 로 올린다(Boot 3.5.16 기본 4.1.135 는 CVE 10건). 소비 앱이 Spring dependency-management 플러그인을 쓰면 BOM 이 다시 내릴 수 있으니 `ext['netty.version'] = '4.1.139.Final'` 를 지정한다 |
| Spring 6.2 CVE | spring-webmvc CVE-2026-47890·47884, spring-webflux CVE-2026-47892 는 7.0.9 에서만 수정됐다. 각각 SSE fragment·XsltView·WebFlux **서버** 함수형 라우팅 문제라 이 라이브러리 사용 범위와 무관. Boot 4 전환 시 해소 |
| 런타임 검증 | 모든 벤더 연동은 공식 스펙 + 단위테스트(로컬 HTTP 서버·가짜 모듈)로 검증했다. 실제 키·계약으로 호출해 보는 검증은 별도로 필요 (KSPAY 는 실제 승인 서버 응답 해석까지 확인) |
| KSPAY 승인 불일치 | 승인 응답의 상점아이디·주문번호·금액이 다르면 예외에 TID 가 없어 자동취소할 수 없다 — 예제는 결과불명(관리자 확인)으로 둔다 |
| static 설정 | Utils 는 설정·도구를 static 으로 든다 — 한 JVM 에 Spring 앱을 둘 이상 띄우면 나중에 뜬 쪽 설정이 이긴다(일반 서비스는 무관) |
| JitPack | `jitpack.yml` 은 있으나 release 태그로 실제 빌드해 본 적은 아직 없다 |

## 모듈 좌표 (artifactId)

| 모듈 | gradle 경로 | artifactId |
|------|-------------|-----------|
| 기본 유틸 | `:modules:lang` | `lang` |
| 공통 | `:modules:common` | `common` |
| 암호화 | `:modules:crypto` | `crypto` |
| WebClient | `:modules:webclient` | `webclient` |
| Swagger | `:modules:swagger` | `swagger` |
| 메일 | `:modules:mail` | `mail` |
| 파일 | `:modules:file` | `file` |
| 본인인증 NICE | `:modules:auth:nice` | `auth-nice` |
| 본인인증 KCB | `:modules:auth:kcb` | `auth-kcb` |
| 결제 토스 | `:modules:payment:toss` | `payment-toss` |
| 결제 NICE | `:modules:payment:nice` | `payment-nice` |
| 결제 KSPAY | `:modules:payment:kspay` | `payment-kspay` |

### 로컬 테스트 (mavenLocal)

```bash
./gradlew publishToMavenLocal
```
```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.corelib:payment-toss:0.1.0' }
```

### 공개 배포 (JitPack)

GitHub 레포(https://github.com/KimTaehyeok01/core-lib)에서 **Releases → Create a new release** 로 태그(예: `v0.1.0`)를 만들면
JitPack 이 그 시점 코드를 빌드해 배포한다(`jitpack.yml` 로 JDK 17 지정). 첫 빌드는 jitpack.io 에서 저장소를 조회하면 시작된다.
멀티 모듈이라 좌표는 `com.github.{GitHub 계정}.{저장소}:{artifactId}:{태그}` 이다.

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.KimTaehyeok01.core-lib:payment-toss:v0.1.0' }
```
