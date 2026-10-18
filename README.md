# core-lib

개인 프로젝트에서 재사용할 공통 모듈 라이브러리. 필요한 모듈만 골라 의존성으로 붙이면 자동설정으로 바로 동작한다.

- JDK 17 / Spring Boot 3.5.x (Servlet·MVC) / Gradle 8.14.3 (wrapper 포함)
- 패키지 `com.corelib.*`, 그룹 `com.corelib`

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
| `example/swagger` | 8105 | `/swagger-ui.html` | - |
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
- 벤더 모듈은 `{벤더}Property`(설정·`validate()`) / `{벤더}Utils`(static 진입점) / `{벤더}Dto`(요청·응답) / `{벤더}Initialize`(자동설정) 로 나눈다.
- 자동설정은 각 모듈의 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 에 등록한다 — 소비 앱 패키지가 달라도 의존성만 붙이면 동작한다.
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
웹 앱이면 전역 예외 핸들러(`CoreExceptionHandler`)가 자동 등록된다.

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
```

### swagger

```yaml
core:
  swagger:
    title: 주문 API
    version: v1
```
JWT 보안 스킴 `bearerAuth` 가 등록된다 — 컨트롤러에 `@SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)`.

## 주의

| 항목 | 내용 |
|------|------|
| KCB 결과 필드명 | 호출 규격(서비스명·form 필드)은 공개 샘플로 확인했지만 결과 필드명(RSLT_NAME 등)은 공개 자료로 확정하지 못했다. 계약 가이드와 다르면 `ConfirmResponse.getRaw()` 로 꺼내 쓰고 매핑을 고친다 |
| netty 버전 | webclient 모듈이 netty 를 4.1.139.Final 로 올린다(Boot 3.5.16 기본 4.1.135 는 CVE 10건). 소비 앱이 Spring dependency-management 플러그인을 쓰면 BOM 이 다시 내릴 수 있으니 `ext['netty.version'] = '4.1.139.Final'` 를 지정한다 |
| Spring 6.2 CVE | spring-webmvc CVE-2026-47890·47884, spring-webflux CVE-2026-47892 는 7.0.9 에서만 수정됐다. 각각 SSE fragment·XsltView·WebFlux **서버** 함수형 라우팅 문제라 이 라이브러리 사용 범위와 무관. Boot 4 전환 시 해소 |
| 런타임 검증 | 모든 벤더 연동은 공식 스펙 + 단위테스트(로컬 HTTP 서버·가짜 모듈)로 검증했다. 실제 키·계약으로 호출해 보는 검증은 별도로 필요 |

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

GitHub 에 push 하고 release 태그를 찍으면 JitPack 이 빌드/배포한다(`jitpack.yml` 로 JDK 17 지정).
멀티 모듈이라 좌표는 `com.github.{GitHub 계정}.{저장소}:{artifactId}:{태그}` 이다.

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.KimTaehyeok01.core-lib:payment-toss:1.0.0' }
```
