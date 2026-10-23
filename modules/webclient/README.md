# webclient

외부 API 호출 도구 — timeout 이 반드시 걸린 WebClient, 실패를 **결과확정 / 결과불명**으로 나누는 분류기, 테스트용 로컬 HTTP 서버.
결제·인증 벤더 모듈이 이 모듈 위에서 동작한다.

## 의존성
```groovy
implementation 'com.corelib:webclient:0.1.0'   // 저장소 설정은 루트 README "설치"
testImplementation testFixtures('com.corelib:webclient:0.1.0')   // MockHttpServer (선택)
```
- `spring-boot-starter-webflux` 가 함께 들어온다(WebClient). 서블릿(MVC) 앱에서도 그대로 쓴다.
- netty 를 **4.1.139.Final** 로 올린다 (Boot 3.5.16 기본 4.1.135 의 CVE 대응). 소비 앱이 Spring dependency-management 플러그인을 쓰면 `ext['netty.version'] = '4.1.139.Final'` 를 지정한다.

설정 없음 — 주소·timeout 은 만들 때 넘긴다.

## 사용법
```java
WebClient client = WebClientUtils.builder("https://api.vendor.com", Duration.ofSeconds(5), Duration.ofSeconds(30))
                                 .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                                 .build();
try {
    Result result = WebClientUtils.post(client, "/v1/orders", null, body, Result.class).block();
} catch (RuntimeException e) {
    switch (WebClientUtils.classify(e)) {
        case REJECTED -> {   // 4xx — 상대가 거절 (결과확정). 오류 본문을 읽어 사용자에게 안내
            VendorError error = WebClientUtils.findClientError(e).map(ex -> ex.getResponseBodyAs(VendorError.class)).orElse(null);
        }
        case NOT_SENT -> {   // 연결 실패 — 요청이 안 나갔다 (결과확정). 다시 보내도 안전
        }
        case UNKNOWN -> {    // timeout·5xx — 처리됐는지 모른다. POST 는 재전송 금지, 조회·망취소로 확인
        }
    }
}
```

## API 요약
| 클래스 | 내용 |
|---|---|
| `WebClientUtils` | `builder(baseUrl, connectTimeout, readTimeout)` · `post(client, uri, headers, body, type, uriVars...)` · `get(client, uri, headers, type, uriVars...)` · `classify(error)` · `findClientError(error)` |
| `FailureType` | `REJECTED` · `NOT_SENT` · `UNKNOWN` · `isConfirmed()` |
| `MockHttpServer` (test-fixtures) | `start()` · `baseUrl()` · `enqueue(MockResponse.ok(json) / status(code, json) / bytes(...) .delayed(d))` · `request(i)` · `requestCount()` · `closedPortUrl()` |

## 주의
- timeout 없는 클라이언트는 만들 수 없다 — `builder` 가 0 이하·null timeout 을 막는다.
- MVC 앱은 `.block()`, WebFlux 앱은 Mono 를 그대로 조합한다 (이벤트 루프에서 `.block()` 금지).
- **결과불명(UNKNOWN)인 POST 를 그대로 다시 보내지 않는다** — 이중 결제·이중 접수가 된다.

## 예제
[`example/webclient`](../../example/webclient) — 택배 접수(POST 결과불명 → 주문번호 조회로 복구)·배송 조회(GET 1회 재시도), 내장 가짜 택배사
