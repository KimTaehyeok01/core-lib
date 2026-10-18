# example-auth-nice

`modules/auth/nice` (NICE 통합인증)로 만든 **본인인증 회원가입·로그인** 예제 — 휴대폰·금융인증서·아이핀·공동인증서.

## 실행
NICE 는 별도 테스트 환경이 없다. 계약 후 서버 출구 IP 를 등록하면 메일로 `client_id`/`client_secret` 을 받는다.
```bash
export NICE_CLIENT_ID=...
export NICE_CLIENT_SECRET=...
export NICE_RETURN_URL=https://내도메인/nice-auth/return   # NICE 에 등록한 도메인
./gradlew :example:auth:nice:bootRun
```
화면: http://localhost:8108 — 키가 없으면 앱은 뜨고 [본인인증 하기] 에서 설정 오류를 알려준다.

## 흐름
1. `POST /api/nice-auth/start` — 토큰·표준창 URL 발급, **txNo 는 세션에만** 저장
2. 브라우저가 표준창을 팝업으로 연다
3. NICE → `GET /nice-auth/return?web_transaction_id=` — 결과 조회·무결성 검증·복호화 (txNo 1회 사용)
4. 서비스가 CI 해시로 회원 조회 → 있으면 **로그인**(휴대폰번호 갱신), 없으면 **가입** (만 14세 미만 차단)
5. `/done.html` 이 부모 창에 `postMessage` 후 닫힘 → `GET /api/nice-auth/result` 로 마스킹된 결과, `GET /api/nice-auth/members/list` 로 회원 목록

## 소비 프로젝트에 옮길 것
- `controller/NiceAuthExampleController` — 세션에 txNo 보관·1회 사용, 콜백 후 리다이렉트
- `service/NiceAuthExampleService` — 회원 저장소만 DB 로 바꾸면 된다 (`ci_hash` 에 unique). CI 해시는 `HashUtils`, 마스킹은 `MaskingUtils`
- `method-type: GET` 유지 — POST 콜백은 다른 사이트에서 오는 POST 라 세션 쿠키(SameSite=Lax)가 안 붙는다
- 이름·CI·휴대폰번호는 화면·로그에 원문으로 남기지 않는다
