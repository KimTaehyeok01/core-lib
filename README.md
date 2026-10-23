# core-lib

개인 프로젝트에서 재사용할 공통 모듈 라이브러리. 결제·본인인증·파일·메일 같은 기능을 프로젝트마다 다시 만들지 않도록,
필요한 모듈만 골라 의존성으로 붙이면 자동설정으로 바로 동작한다.

- `modules/` — 라이브러리 본체 (배포 대상)
- `example/` — 모듈 사용법 가이드. 각 모듈을 서비스 코드에서 어떻게 부르는지 보여주는 실행 가능한 예제 (배포 제외)
- JDK 17 / Spring Boot 3.5.x (Servlet·MVC) / Gradle 8.14.3 (wrapper 포함), 패키지 `com.corelib.*`

## 시작하기 — 새 프로젝트에 붙이는 3단계

1. **의존성** — 필요한 모듈만 ([모듈 목록](#모듈-목록), 저장소 설정은 [설치](#설치))
   ```groovy
   dependencies {
       implementation 'com.corelib:common:0.1.0'
       implementation 'com.corelib:payment-toss:0.1.0'
   }
   ```
2. **yml** — 모듈 README 의 "설정" 절 (키는 `${환경변수}` 로만)
3. **서비스에서 호출** — `TossPayUtils.confirm(...)` 처럼 static 유틸을 부른다. 흐름은 `example/{모듈}/.../service/` 를 그대로 따라 한다

앱을 실행하면 콘솔의 **기동 안내**에 붙인 모듈의 초기화 결과가 나온다 — `미초기화` 가 보이면 적힌 설정을 넣으면 된다.

## 구조

```
core-lib/
└── modules/
    ├── lang/        문자열·컬렉션·난수 문자열·날짜(KST)·개인정보 마스킹 (순수 자바, 최하단)
    ├── common/      공통 예외·에러코드·응답 포맷·전역 예외 핸들러·검증
    ├── crypto/      AES(GCM/CBC)·RSA·PEM·SEED·HMAC·SHA·Hex·Base64(url)·PBKDF2
    ├── webclient/   외부 API 호출 (WebClient + timeout + 결과확정/불명 분류)
    ├── web/         서블릿 웹 공통 — 클라이언트 IP·traceId·요청 로그·비동기 MDC·Security 기본 체인·헬스체크
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
의존 방향: `lang` ← `common` ← (`crypto`, `webclient`, `web`, swagger, mail, file) ← 벤더 모듈(auth/*, payment/*).

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
| `example/web` | 8113 | 고객 문의 접수 (IP 별 제한·추적번호·관리자 API) | 관리자 API 는 `ADMIN_API_KEY` |

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

## 모듈 목록

모듈마다 README 에 의존성·설정·사용법·에러코드·주의가 있다.

| 모듈 | artifactId | 내용 | 계약 필요 |
|------|-----------|------|-----------|
| [lang](modules/lang) | `lang` | 문자열·컬렉션·난수·날짜(KST)·마스킹 | - |
| [common](modules/common) | `common` | ApiResponse·CoreException·전역 예외 핸들러·검증·기동 안내 | - |
| [crypto](modules/crypto) | `crypto` | AES·RSA·PEM·SEED·HMAC·SHA·PBKDF2 | - |
| [webclient](modules/webclient) | `webclient` | 외부 API 호출 + 실패 분류 + MockHttpServer | - |
| [web](modules/web) | `web` | 클라이언트 IP·traceId·요청 로그·Security·헬스체크 | - |
| [swagger](modules/swagger) | `swagger` | OpenAPI 자동 설정·공통 에러 응답 | - |
| [mail](modules/mail) | `mail` | SMTP 메일 발송 | - |
| [file](modules/file) | `file` | 로컬·S3 파일 저장 | - |
| [auth/nice](modules/auth/nice) | `auth-nice` | NICE 통합인증 본인확인 | NICE |
| [auth/kcb](modules/auth/kcb) | `auth-kcb` | KCB 휴대폰 본인확인 | KCB (jar·라이선스) |
| [payment/toss](modules/payment/toss) | `payment-toss` | 토스페이먼츠 | 가입만 (테스트 키) |
| [payment/nice](modules/payment/nice) | `payment-nice` | 나이스페이먼츠 | 가입만 (샌드박스) |
| [payment/kspay](modules/payment/kspay) | `payment-kspay` | KSNET KSPAY | 테스트 상점 공개, 취소는 계약 |

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

## 설치

빌드 설정의 그룹은 `com.corelib`, 버전은 `0.1.0` 이다. 의존성 좌표는 `com.corelib:{artifactId}:0.1.0`.

### 내 PC 에서 바로 (mavenLocal)
```bash
./gradlew publishToMavenLocal
```
```groovy
repositories { mavenLocal(); mavenCentral() }
```
이 PC 에서만 동작한다 — 다른 PC·배포 서버에서 빌드하려면 아래 원격 저장소가 필요하다.

### GitHub Packages (다른 PC·서버에서 쓰기)
의존성 줄에는 `com.corelib:{artifactId}:0.1.0` 만 보인다. 저장소 주소에만 계정 이름이 들어간다(GitHub Packages 규칙).

**1. 토큰 만들기** — GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)**
- 올리는 PC: `write:packages` (`read:packages` 포함)
- 받기만 하는 PC·서버: `read:packages`
- GitHub Packages 의 Maven 저장소는 classic 토큰만 된다. 공개 레포여도 받을 때 토큰이 필요하다.

**2. 토큰 저장** — 코드·레포에 넣지 않는다. `~/.gradle/gradle.properties` (윈도우 `C:\Users\{사용자}\.gradle\gradle.properties`)
```properties
gpr.user=GitHub아이디
gpr.key=발급받은토큰
```
또는 환경변수 `GITHUB_ACTOR`, `GITHUB_TOKEN` (CI 에서는 이쪽).

**3. 올리기** (core-lib 에서, 버전을 올릴 때마다)
```bash
./gradlew publish
```
- 같은 버전은 다시 올릴 수 없다 — 고쳤으면 루트 `build.gradle` 의 `version` 을 올린다 (0.1.0 → 0.1.1).
- 올릴 코드는 main 에 합친 뒤 올리는 것을 권장한다.

**4. 쓰는 프로젝트** (`build.gradle`)
```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/KimTaehyeok01/core-lib')
        credentials {
            username = findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR')
            password = findProperty('gpr.key') ?: System.getenv('GITHUB_TOKEN')
        }
    }
}
dependencies {
    implementation 'com.corelib:common:0.1.0'
    implementation 'com.corelib:payment-toss:0.1.0'
}
```

### JitPack (참고)
JitPack 을 쓰면 좌표가 `com.github.KimTaehyeok01.core-lib:{artifactId}:{태그}` 로 바뀐다 (토큰 없이 받을 수 있지만 그룹에 계정 이름이 들어간다). `jitpack.yml` 은 남겨 두었다.
