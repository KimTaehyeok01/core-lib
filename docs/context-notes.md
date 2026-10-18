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
