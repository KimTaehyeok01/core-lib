# auth/kcb

KCB 휴대폰 본인확인 (OkCert3 팝업) — 모듈 토큰 발급 → 팝업 form → 결과 조회.
KCB 가 계약 후 주는 OkCert3 jar 를 리플렉션으로 호출한다 (jar 는 공개 배포되지 않아 core 에 포함하지 않는다).

## 의존성
```groovy
implementation 'com.corelib:auth-kcb:0.2.0'   // 저장소 설정은 루트 README "설치"
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
    popup-url: https://safe.ok-name.co.kr/CommonSvl   # 기본값 (테스트 서버 주소는 계약 가이드) — https 만 (로컬 http 허용)
    timeout: 10s                                 # OkCert 호출 1회 대기 상한 (기본 10s) — 넘으면 KCB-EXTERNAL
```
- `cp-cd` 가 있는데 필수값이 비었거나, `popup-url` 이 https 가 아니거나, `return-url` 이 http(s) 절대 URL·250 byte 이하가 아니거나, `timeout` 이 0 이하거나, 라이선스 파일을 못 읽으면 **기동이 실패한다**. 메시지에는 키 이름만 나온다 (값·경로 없음).

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
| C400 | 400 | `start` 의 returnUrl 이 http(s) 절대 URL 이 아니거나 250 byte 초과 — 모듈 호출 전에 막는다 |
| KCB-MODULE | 500 | OkCert jar 가 없음 — 기동 안내에도 "OkCert jar 없음" 으로 표시 |
| KCB-LICENSE | 500 | 라이선스 파일 없음 |
| KCB-RESULT-INCOMPLETE | 500 | 결과 필수 항목 없음 |
| KCB-EXTERNAL | 502 | KCB 통신 실패 · `auth.kcb.timeout` 초과 |

## 주의
- **결과 필드명(RSLT_NAME 등)은 공개 자료로 확정하지 못했다.** 계약 가이드와 다르면 `person.getRaw()` 로 꺼내 쓰고 매핑을 고친다.
- 테스트 환경 주소·target 값은 계약 가이드를 따른다.
- OkCert 호출은 전용 데몬 스레드 4개에서 돌고, 요청 스레드는 `timeout` 까지만 기다린다. 동시 호출이 4건을 넘으면 큐에서 기다리다 같은 timeout 으로 끝난다.
  - 시간 초과 시 호출 스레드를 인터럽트하지만, jar 가 소켓에서 대기 중이면 jar 내부 timeout 까지 그 스레드는 묶일 수 있다. KCB 가 멈추면 이후 호출은 timeout 뒤 KCB-EXTERNAL 로 끝난다.
  - 결과 조회(`confirm`)가 시간 초과되면 토큰당 1회 조회라 다시 인증해야 할 수 있다.

## 예제
[`example/auth/kcb`](../../../example/auth/kcb) — 본인확인 → CI 로 회원 가입/로그인 (테스트는 가짜 OkCert 클래스로)
