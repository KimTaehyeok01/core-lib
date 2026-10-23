# auth/kcb

KCB 휴대폰 본인확인 (OkCert3 팝업) — 모듈 토큰 발급 → 팝업 form → 결과 조회.
KCB 가 계약 후 주는 OkCert3 jar 를 리플렉션으로 호출한다 (jar 는 공개 배포되지 않아 core 에 포함하지 않는다).

## 의존성
```groovy
implementation 'com.corelib:auth-kcb:0.1.0'   // 저장소 설정은 루트 README "설치"
implementation files('libs/OkCert3-java1.5-2.2.3.jar')   // 계약 후 받은 KCB jar — 저장소에 올리지 않는다
```

## 설정
```yaml
auth:
  kcb:
    cp-cd: ${KCB_CP_CD}                          # 회원사코드 12자리 — 없으면 자동설정이 꺼진다
    license-path: /secure/kcb/V..._license.dat   # KCB 가 준 라이선스 파일 (저장소에 넣지 않는다)
    target: PROD                                 # PROD(기본) · TEST
    return-url: https://my.domain/kcb/return
    site-name: 내 서비스
    site-url: my.domain
    request-cause: SIGN_UP                       # SIGN_UP · ADULT_VERIFICATION · MEMBER_UPDATE · FIND_PASSWORD · PURCHASE · ETC
    popup-url: https://safe.ok-name.co.kr/CommonSvl   # 기본값 (테스트 서버 주소는 계약 가이드)
```

## 사용법
```java
// 1. 시작 — 화면이 popupUrl 로 formFields 를 팝업에 POST
KcbAuthDto.StartResponse start = KcbAuthUtils.start(null);   // 요청값 비우면 설정값 사용
return Map.of("popupUrl", start.getPopupUrl(), "formFields", start.formFields());   // tc · cp_cd · mdl_tkn · target_id

// 2. return_url (?mdl_tkn=...) — 토큰당 1회
KcbAuthDto.ConfirmResponse person = KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn(mdlTkn).build());
// person.getCi() 로 회원 조회·가입
```

## API 요약
| 메서드 | 반환 |
|---|---|
| `start(StartRequest{returnUrl, requestCause})` (null 가능) | `StartResponse` — `popupUrl` · `formFields()` |
| `confirm(ConfirmRequest{mdlTkn})` | `ConfirmResponse` — `name` · `birthday`(yyyyMMdd) · `sexCd`(M·F) · `nationalCd`(L 내·F 외) · `ci` · `di` · `telComCd` · `telNo` · `raw`(원본) |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| `KCB_{code}` | 4xx | KCB 가 거절 (예: 유효하지 않은 토큰) |
| KCB-MODULE | 500 | OkCert jar 가 없음 — 기동 안내에도 "OkCert jar 없음" 으로 표시 |
| KCB-LICENSE | 500 | 라이선스 파일 없음 |
| KCB-RESULT-INCOMPLETE | 500 | 결과 필수 항목 없음 |
| KCB-EXTERNAL | 502 | KCB 통신 실패 |

## 주의
- **결과 필드명(RSLT_NAME 등)은 공개 자료로 확정하지 못했다.** 계약 가이드와 다르면 `person.getRaw()` 로 꺼내 쓰고 매핑을 고친다.
- 테스트 환경 주소·target 값은 계약 가이드를 따른다.

## 예제
[`example/auth/kcb`](../../../example/auth/kcb) — 본인확인 → CI 로 회원 가입/로그인 (테스트는 가짜 OkCert 클래스로)
