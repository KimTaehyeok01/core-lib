# context-notes

작업 중 내린 결정과 근거. 새 결정은 아래에 이어 붙인다.

## 구조
- 모듈은 `modules/` 아래, 브랜드가 여러 개인 도메인(auth, payment)은 브랜드별 독립 모듈 — 필요한 브랜드만 의존하게.
- gradle 경로 = 폴더 경로(`:modules:payment:toss`) — 경로가 다르면 IntelliJ 가 그룹 포함 긴 모듈명을 표시하고, 숨은 부모 프로젝트(`:auth`)가 루트에 빌드 산출물을 만든다.
- 플러그인은 자식 없는 실제 모듈에만 적용 — 묶음 폴더가 빈 jar 를 만들지 않게.
- artifactId 는 경로에서 생성(`auth-nice`, `payment-nice`) — 이름 겹침 충돌 방지.
- 패키지 `com.corelib` — 사용자 선택(GitHub ID 기반 `io.github.kimtaehyeok` 대체).

## 회사 코드
- 사용자가 공유한 `com.lemonhc.core`(KSPAY/NICE) 코드는 복사하지 않음 — 재직 중 작성 코드는 회사 소유, 공개 레포에 사내 연동 정보 노출 위험.
- 구조·관례(Property/Utils/Dto/Initialize 분리, Javadoc 톤, addErrorLogData, 벤더코드 패스스루)만 따르고 구현은 공개 스펙으로 새로 작성.

## 기술 선택
- HTTP 는 WebClient 대신 RestClient — MVC 앱에서 추가 의존성(webflux·netty) 없이 동기 호출, `.block()` 불필요.
- Spring 의존은 compileOnly(소비 앱이 보유), 모듈 존재 이유인 의존성만 api(swagger→springdoc, mail→starter-mail, 검증 쓰는 모듈→starter-validation).
- file 은 저장소 전략 교체가 자연스러워 빈(`FileStorage`) + static 진입점(`FileUtils`) 병행.
- NICE 통합인증 봉인/평문 해석은 전용 ObjectMapper — 소비 앱 naming strategy 가 암호 포맷을 바꾸지 않게.

## 의존성 (cooldown·OSV)
- Spring Boot BOM 3.5.0 → 3.5.16 — 3.5.0 은 spring-boot/spring-web CVE.
- AWS S3 SDK 2.55.13 — 2.56.0 은 3일차라 minor cooldown(14일) 미달.
- springdoc 2.8.17, Lombok 1.18.46, hibernate-validator 8.0.3 — OSV PASS.
- 잔여: spring-webmvc 6.2.19 CVE-2026-47890·47884 (7.0.9 에서만 수정, 미사용 기능).

## 벤더별 판단
- toss: 결과불명 시 승인 재전송 대신 조회 API 재확인(재전송은 ALREADY_PROCESSED).
- nicepay: 결과불명 시 매뉴얼 지침대로 자동 망취소. 승인 미발생 건의 망취소 응답 코드는 미문서 → 실패로 보고 관리자 확인(보수적).
- nice 통합인증: 가이드 내 IV 위치 서술 충돌 → 모든 언어 샘플 코드(디코딩 후 앞 16바이트) 기준. 토큰 Basic 은 base64url 무패딩이라 setBasicAuth 미사용.
- KCB: 본인확인은 계약 jar 필요로 보류, 결제 상품은 없음.

## 2차 — 회사 core 수준으로 확장 (2026-10-18)
- 사용자 선택으로 HTTP 를 RestClient → **WebClient**(webflux) 로 전환. `http` 모듈 삭제, `webclient` 모듈 신설.
- WebClient 테스트는 MockRestServiceServer 가 안 되므로 reactor-netty 로 실제 로컬 서버(`MockHttpServer`, testFixtures)를 띄운다 — 응답 timeout·연결 거부를 실제 소켓으로 재현.
- netty: Boot 3.5.16 기본 4.1.135 에 CVE 10건(CRITICAL CVE-2026-75595 포함) → `netty-bom 4.1.139.Final` 을 webclient 에 api platform 으로 걸어 소비 앱까지 상향. 4.1.137 부터 전부 수정, 4.1.139 는 11일차 patch(7일 cooldown 통과)·OSV PASS.
- spring-webflux CVE-2026-47892 는 WebFlux 서버 함수형 엔드포인트 문제라 WebClient(클라이언트)만 쓰는 우리와 무관.
- `lang` 신설, StringUtils 를 common → lang 으로 이동 (common 이 lang 을 api 로 노출해 기존 사용처는 import 만 변경).
- `crypto` 신설 — 표준 벡터(SHA-256, HMAC RFC 4231, PBKDF2 RFC 7914)로 검증. nicepay 서명·NICE 인증 암복호화·txNo 봉인을 crypto 로 통일.
- KCB: 공개 래퍼(github.com/buYoung/KCB_OkCert_Nodejs, seungminio/okcert)로 `kcb.module.v3.OkCert#callOkCert(target, cpCd, svcName, licensePath, json)`·`IDS_HS_POPUP_START/RESULT`·팝업 form 확인. jar 비공개라 리플렉션 호출, 테스트는 같은 이름의 가짜 클래스로 실제 리플렉션 경로 검증. 결과 필드명은 공개 자료로 미확정 → raw 맵 함께 반환.
- payment/kcb 삭제 — KCB 는 결제(PG) 상품이 없다.
- 자동설정 등록은 Boot 3 방식 `META-INF/spring/...AutoConfiguration.imports` (Boot 2 의 spring.factories 아님).

## 3차 — payment/kspay (2026-10-18)
- 사용자 지정: 승인 = KSPAY 통합모듈(web_host recv_post.jsp), 취소 = KSNET webfep 가이드(paydev.ksnet.co.kr/kspay/webfep/doc).
- 회사 KSPAY 코드는 붙여넣지 않고 같은 구조(Property/Dto/Utils/Initialize)로 새로 작성 — 프로토콜 사실은 webfep 공식 문서·공개 샘플(github.com/Renyu106/KSPAY-PAYMENT-API) 기준.
- 공개 자료에 없어 사용자가 준 검증 코드의 동작을 따른 부분: sndActionType=1, sndAmount 전송, storeid 회신 필드, 주문번호 byte 상한(PC 50/모바일 30), 결과불명 시 같은 결제키 1회 재전송(재조회).
- webfep 에는 거래 조회 API 가 없다 — 승인 결과불명 복구는 재전송뿐. 재전송 응답 거절은 첫 요청 승인 여부를 못 가려 RESULT_UNKNOWN.
- 취소 본문은 가이드 예시대로 수단별 키를 모두 문자열로, 미사용 값은 "" 로 보낸다.
- 테스트 서버(MockHttpServer)에 바이트 응답(MockResponse.bytes) 추가 — MS949 승인 응답 재현용.

## 4차 — example/ (2026-10-18)
- 최상위 `example/` 를 `modules/` 와 같은 모양으로, 계층은 Controller/Service/Dto 만 (사용자 지정, 회사 방식). 서비스가 core 유틸을 실제로 호출한다.
- 예제 group 을 `com.corelib.example` 로 분리 — 모듈과 같은 좌표(com.corelib:lang)면 Gradle 이 순환 의존으로 본다. 예제는 배포하지 않고 jar 비활성.
- 예제는 boot 플러그인만 쓰고 dependency-management 플러그인은 쓰지 않는다 — netty 4.1.139 상향이 BOM 에 다시 내려가지 않게.
- 키가 비면 벤더 자동설정이 꺼진다 → 앱은 뜨고 호출 시 "미초기화" 안내. 테스트는 MockHttpServer(http loopback 허용)를 base-url 로.
- 결제 예제 공통: 금액은 주문 생성 때 서버가 정해 보관, 콜백 값(쿼리·form)은 대조용, 콜백 중복 시 재승인 안 함, 결과불명은 UNKNOWN.
- KSPAY 결제창: KSNET 자료실 통합모듈 V1.4 PC 공식 샘플(JSP utf-8)로 확인 — `kspay_web_ssl.js` 의 `_pay(form)`, form 이름 KSPayWeb, sndReply 절대 URL, rcv 페이지가 `opener.eparamSet → goResult`(취소는 `reCnclType=1 → mcancel`), goResult 가 form 을 결과 페이지로 submit. 승인 sndActionType=1·sndAmount·재전송 동작도 샘플 주석으로 확인.
- KSPAY 승인 불일치(MID/ORDER/AMOUNT_MISMATCH) 예외에는 TID 가 없어 예제는 자동취소 대신 UNKNOWN(관리자 확인). 토스·나이스페이는 paymentKey/tid 를 알고 있어 즉시 자동취소.
- JitPack 멀티모듈 좌표는 `com.github.KimTaehyeok01.core-lib:{artifactId}:{tag}`.
- common 전역 핸들러 버그 수정: `Exception` 일괄 처리가 Spring MVC 요청 오류(깨진 JSON·파라미터 누락·타입 불일치·405·정적 리소스 404)까지 C500 으로 바꾸던 것을 4xx(C400/C404/C405…)로. 예제 앱 실기동 중 발견.
- 예제 실기동 확인(kspay 8112): 화면 200, 주문 생성, 오류 매핑, 가짜 결제키로 실제 KSNET 승인 서버 호출 → "결제 정보가 없거나 만료" 정상 해석.

## 5차 — 예제를 실제 서비스로 (2026-10-18)
- 사용자 지적: 예제가 기능 시연(API 하나에 유틸 하나)이거나 핵심 업무가 주석("실서비스: ...")으로만 있었다 → 예제 서비스가 core 유틸로 실제 업무를 끝까지 수행하도록 재작성.
  - auth/nice·kcb: CI 해시(HashUtils)로 회원 조회 → 로그인(번호 갱신) / 가입, 만 14세 미만 차단, 회원 목록(마스킹)
  - lang: 비회원 게시판 — 입력 정리(StringUtils), 태그(CollectionUtils), 공유코드·삭제 PIN(RandomStringUtils), 작성자 마스킹
  - crypto: 개인정보 암호화 고객 — AES-GCM 컬럼 암호화, HMAC 이메일 검색 인덱스, PBKDF2 비밀번호, 로그인 실패 응답 통일
  - webclient: 택배 접수·배송 조회 — POST 결과불명은 재전송 대신 주문번호 조회로 복구, GET 은 1회 재시도, 택배사 코드 COURIER_{code}. bootRun 용 가짜 택배사 내장
  - mail: 이메일 인증코드 — 해시 보관·상수 시간 비교·재발송 대기·5회 오입력 폐기
- 예제마다 손으로 짠 마스킹 함수가 있었다 → lang 에 `MaskingUtils`(name·mobile·email) 추가하고 예제는 그것만 쓴다. 휴대폰 마스킹 형식은 010-****-5678 로 통일.
- common·swagger·file·payment 예제는 이미 실제 흐름(재고 차감·상품·첨부·주문 결제)이라 유지.

## 6차 — 기동 안내·Swagger 공통 에러 (2026-10-18)
- 기동 안내(common): ApplicationReadyEvent 에 노란 CORE 로고 + Local/Network/Swagger 주소 + 모듈 초기화 결과를 로그 한 번(여러 줄)으로. `core.startup-log.enabled` 로 끈다.
- 모듈 보고는 `StartupReporter` 빈 — 벤더 Initialize 는 키가 없으면 꺼지므로 보고 클래스(`XxxStartupReport`)는 조건 없는 별도 자동설정으로 두고, Initialize 빈 유무로 초기화/미초기화를 판단한다. 키·비밀번호·회원사코드·라이선스 경로는 찍지 않고, 이메일은 마스킹. KSPAY mid 는 결제창 form 으로 나가는 공개값이라 그대로.
- 색은 Spring AnsiOutput 을 따른다 — 무료 IntelliJ 일반 실행은 감지가 꺼져 색이 빠지므로 예제 yml 에 `spring.output.ansi.enabled: always`.
- Swagger 공통 에러: OperationCustomizer 로 codes(기본 400·404·500)는 전부, secured-codes(기본 401·403)는 @SecurityRequirement API 에만. 직접 적은 응답 코드는 유지. 본문 스키마 CoreErrorResponse 는 OpenApiCustomizer 로 넣어 소비 앱이 OpenAPI 빈을 직접 만들어도 붙는다.
- Swagger UI 기본값(doc-expansion none, models depth -1)은 EnvironmentPostProcessor 로 맨 뒤 우선순위에 넣어 소비 앱 yml 이 이긴다.
- IntelliJ 에서 .imports 파일이 초록 아이콘이 아닌 건 유료 Spring 플러그인 표시 차이 — 동작과 무관.

## 7차 — base 보강 (2026-10-24, feat/base-web-module)
- 사용자 회사 core 문서는 기능 목록으로만 참고 — 문서의 코드·문장·API 설계를 옮기지 않고 JDK·Spring 공식 동작 기준으로 새로 설계.
- 사용자 요청으로 Java 파일 첫 줄 역할 주석 전부 제거, 새 파일에도 달지 않는다 (전역 CLAUDE.md 6번과 충돌 — 사용자에게 알림).
- BouncyCastle bcprov-jdk18on 1.86 (2026-09-11 출시, minor cooldown 14일 통과, OSV 0건) — 1.85 미만은 2026 취약점(타이밍 채널·ASN.1 등) 있음. SEED·PKCS#1 용도, 경량 API(SEEDEngine)만 써서 JVM 전역 Provider 등록 안 함.
- web 모듈 Security 는 소비 앱이 spring-boot-starter-security 를 넣었을 때만 동작(compileOnly + ConditionalOnClass) — 의존성만으로 보안이 강제되지 않게.
- 클라이언트 IP 는 신뢰 프록시(기본 루프백·사설망)에서 온 요청일 때만 X-Forwarded-For 를 오른쪽부터 따라간다 — 헤더 위조 방지.
- SEED 구현은 RFC 4269 테스트 벡터(키 0, 평문 00..0F → 5EBAC6E0...)로 검증.
- 요청 로그 마스킹은 키 이름 끝 일치 — 부분 일치는 ci 가 price·city 를 가려 버림. 실기동에서 name 이 그대로 찍혀 name·address 를 기본 목록에 추가(goodsName 등도 가려지지만 개인정보 노출보다 안전한 쪽).
- 컨트롤러 안의 AccessDeniedException 은 전역 핸들러(Exception)에 먼저 잡혀 500 이 되므로 web 에 최우선 SecurityExceptionHandler(403·401) 추가.
- Boot 임시 비밀번호 로그를 막으려고 사용자 저장소가 없을 때만 빈 UserDetailsService 등록(ConditionalOnMissingBean).
- 테스트 클래스 안의 static @RestController 는 Boot 테스트의 TypeExcludeFilter 로 스캔에서 빠진다 — @Bean 으로 등록.

## 8차 — 배포 (2026-10-24)
- 좌표에 계정명이 들어가지 않도록 GitHub Packages 로 결정 — 좌표 com.corelib:{artifactId}:0.1.0, 저장소 URL 에만 계정명(규칙상 불가피).
- GitHub Packages Maven 은 공개 레포도 받을 때 토큰 필요(classic PAT read:packages), 올릴 때 write:packages. credentials 는 gpr.user/gpr.key 또는 GITHUB_ACTOR/GITHUB_TOKEN — 코드에 리터럴 없음.
- 모듈별 README 13개 작성, 루트 README 의 모듈 사용법은 링크 표로 대체(두 곳 관리로 어긋나는 것 방지).

## 9차 — A묶음 (2026-10-24, feat/jwt-otp-excel-image)
- 회사 docs 는 기능 목록으로만 참고 — 구현은 RFC·라이브러리 공식 문서 기준.
- 의존성: nimbus-jose-jwt 10.10 (2026-09-17, OSV 0), poi-ooxml 5.5.1 (2025-11-26, OSV 0 — 6.0.0 은 2026-10-19 출시로 major cooldown 30일 미달), imageio-webp 3.15.3 (2026-10-06, OSV 0). POI 하위(commons-compress 1.28.0·commons-io 2.21.0·xmlbeans 5.3.0) OSV 0.
- CSV 는 RFC 4180 수준이라 직접 구현 (opencsv 의존 안 함).
- OTP 는 crypto HMAC 로 직접 구현 — RFC 6238 부록 B 벡터로 검증.
- jwt: 검증 때 헤더 alg 를 설정과 비교 — alg:none·공개키를 HMAC 비밀값으로 쓰는 바꿔치기를 테스트로 확인. Bearer 필터는 빈으로 만들지 않는다(서블릿 필터 이중 등록 방지) — CoreSecurityCustomizer 안에서 생성. 토큰을 보냈는데 만료·위조면 바로 401(JWT-EXPIRED·INVALID·TYPE) — 클라이언트가 갱신 여부를 판단.
- jwt 예제: 리프레시 회전 + 재사용 감지(이미 쓴 jti 가 오면 전체 폐기). core 는 무상태라 jti 저장은 예제 서비스 몫.
- otp: RFC 6238 부록 B 9개 벡터(SHA1·256·512) 통과. 재사용 방지는 timeStep 을 호출측이 저장, 잠금도 호출측.
- excel: ReadResult 에 rowNumbers 추가 — 업무 검증 오류를 엑셀 행 번호로 알리려고(예제에서 필요해서 모듈 보강). 날짜 해석 오류 메시지에 입력값을 넣지 않게 고정 문장.
- image: EXIF 방향 5·7 변환식은 좌표 대입으로 검증, 6·3·8 은 테스트로. ImageIO.setUseCache(false)(임시 파일 안 남김), scanForPlugins(실행 jar 의 webp 플러그인 등록).
