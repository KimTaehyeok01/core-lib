# checklist

- [x] 멀티모듈 뼈대 + Gradle wrapper + git init
- [x] modules/ 폴더 구조, gradle 경로 = 폴더 경로
- [x] lang — StringUtils·CollectionUtils·RandomStringUtils
- [x] common — 에러코드·예외(addErrorLogData, ErrorCode.of)·ApiResponse·전역 핸들러 자동설정·ValidateUtils
- [x] crypto — AES(GCM/CBC)·HMAC·SHA·Base64(url)·PBKDF2
- [x] webclient — WebClientUtils(timeout, 결과확정/불명 분류) + MockHttpServer(testFixtures), netty CVE 상향
- [x] swagger — OpenAPI 기본 빈 + bearerAuth
- [x] mail — MailUtils/MailDto/MailInitialize
- [x] file — Local/S3 저장소, FileUtils, 경로 탈출·위험 확장자 차단
- [x] payment/toss — 승인/취소/조회, 재조회, 가상계좌, 오류코드 패스스루
- [x] payment/nice — 인증결과 서명검증, 승인, 자동 망취소, 취소/조회
- [x] auth/nice — 토큰/표준창/결과, PBKDF2+AES-GCM 복호화, 무결성 검증, txNo 봉인
- [x] auth/kcb — OkCert3 팝업 시작/결과, 리플렉션 호출
- [x] payment/kspay — 승인(통합모듈 MS949·재전송)·취소(webfep 카드/계좌/휴대폰/가상계좌)
- [x] example/ 12개 — Controller/Service/Dto + application.yml + README + 가짜 벤더 서버 전 흐름 테스트
- [x] 결제·인증·파일·메일 예제 화면(static HTML)
- [x] 루트 README 예제 섹션, JitPack 좌표 정정, jitpack.yml(JDK 17)
- [x] lang MaskingUtils, 예제를 실제 업무 흐름으로 재작성(회원가입·게시판·암호화 고객·택배·이메일 인증)
- [x] 전역 핸들러 — Spring MVC 요청 오류 4xx 매핑
- [x] 기동 안내(CORE 로고·주소·모듈 초기화 결과), Swagger 공통 에러 응답 yml 설정
- [x] GitHub push (first commit)
- [ ] KCB 결과 필드명을 계약 가이드로 확정
- [ ] 실제 테스트키·계약으로 벤더 런타임 호출 검증
- [ ] JitPack 릴리스(v0.1.0) 빌드 확인

## base 보강 (feat/base-web-module)
- [x] 브랜치 분기, Java 첫 줄 주석 제거(사용자 요청)
- [x] lang — DateTimeUtils
- [x] crypto — HexUtils · RsaUtils · PemUtils · SeedUtils (BouncyCastle 1.86)
- [x] web — 클라이언트 IP · traceId/MDC · 비동기 MDC · 요청 로그
- [x] web — Security 기본 체인(선택) · Actuator 헬스체크 기본값 · 기동 안내
- [x] example/web (8113)
- [x] 전체 빌드 · 실기동 · README
- [x] 모듈별 README 13개 (의존성·설정·사용법·API·에러코드·주의·예제) — 루트 README 는 링크 표로
- [x] 원격 배포 — GitHub Packages (좌표 com.corelib:{artifactId}:0.1.0, 토큰은 gradle.properties·환경변수)
- [ ] 첫 업로드 (./gradlew publish) — 사용자 토큰 필요
