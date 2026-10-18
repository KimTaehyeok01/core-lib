# example-auth-kcb

`modules/auth/kcb` (KCB 휴대폰 본인확인, OkCert3 팝업)로 만든 **본인확인 회원가입·로그인** 예제.

## 실행
KCB 계약 후 받는 3가지가 필요하다 — **OkCert3 jar**, **라이선스 파일(.dat)**, **회원사코드(12자리)**.
```bash
cp OkCert3-java1.5-*.jar example/auth/kcb/libs/      # 저장소에는 올리지 않는다 (.gitignore)
export KCB_CP_CD=V00000000000
export KCB_LICENSE_PATH=/secure/kcb/V00000000000_AES_license.dat
export KCB_RETURN_URL=https://내도메인/kcb/return
./gradlew :example:auth:kcb:bootRun
```
화면: http://localhost:8109 — jar 가 없으면 [본인확인 하기] 에서 "KCB OkCert 모듈이 없습니다" 안내가 뜬다.

## 흐름
1. `POST /api/kcb/start` — 모듈 토큰(MDL_TKN) 발급, 팝업 form 값(tc·cp_cd·mdl_tkn·target_id) 반환
2. 화면이 팝업 창에 form 을 POST (`https://safe.ok-name.co.kr/CommonSvl`)
3. KCB → `GET /kcb/return?mdl_tkn=` — 결과 확인(토큰당 1회)
4. 서비스가 CI 해시로 회원 조회 → 있으면 **로그인**(휴대폰번호 갱신), 없으면 **가입** (만 14세 미만 차단)
5. `/done.html` 이 부모 창에 알림 → `GET /api/kcb/result` 로 마스킹된 결과, `GET /api/kcb/members/list` 로 회원 목록

## 소비 프로젝트에 옮길 것
- `service/KcbAuthExampleService` — 회원 저장소만 DB 로 바꾸면 된다 (`ci_hash` 에 unique). CI 해시는 `HashUtils`, 마스킹은 `MaskingUtils`

## 주의
- 결과 필드명(RSLT_NAME 등)은 공개 자료로 확정하지 못했다 — 계약 가이드와 다르면 서비스에서 `person.getRaw()` 로 꺼내고 모듈 매핑을 고친다
- 테스트 서버 팝업 주소는 계약 가이드를 따른다 (`auth.kcb.popup-url`)
