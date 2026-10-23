# common

모든 모듈의 토대 — 공통 응답 포맷(`ApiResponse`), 예외(`CoreException`)·에러코드, 전역 예외 핸들러, 요청 검증, 기동 안내.
`lang` 을 함께 노출한다 (`api`).

## 의존성
```groovy
implementation 'com.corelib:common:0.1.0'   // 저장소 설정은 루트 README "설치"
implementation 'org.springframework.boot:spring-boot-starter-validation'   // ValidateUtils 를 쓸 때 (검증 구현체)
```

## 설정
```yaml
core:
  startup-log:
    enabled: true          # 기동 안내 (기본 true)
spring:
  output:
    ansi:
      enabled: always      # IntelliJ 일반 실행에서도 CORE 로고 노란색
```

## 사용법
```java
// 응답
return ApiResponse.success(data);          // {"success":true,"data":...}
return ApiResponse.success();              // 데이터 없는 성공

// 예외 — 전역 핸들러가 ApiResponse 로 바꾼다
throw new CoreException(CommonErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다.")
    .addErrorLogData("itemId", itemId);    // 로그에만 남고 응답에는 안 나간다

// 도메인 에러코드 — 보통은 enum 으로 모아 ErrorCode 를 구현한다
ErrorCode SOLD_OUT = ErrorCode.of("ITEM_SOLD_OUT", "품절된 상품입니다.", 409);

// 요청 DTO 의 jakarta 어노테이션 검증 — 위반이면 C400
ValidateUtils.validate(request);

// 목록 응답 — {content, page, size, totalElements, totalPages, first, last}
return ApiResponse.success(PageResponse.of(items, page, size, total));
return ApiResponse.success(PageUtils.from(repository.findAll(PageUtils.pageable(page, size, Sort.by("id").descending()))).map(Dto::from));
```

## 자동으로 켜지는 것
| 기능 | 조건 | 내용 |
|---|---|---|
| 전역 예외 핸들러 `CoreExceptionHandler` | 서블릿 웹 앱 | `CoreException` → 담긴 코드·상태 / Spring MVC 요청 오류(깨진 JSON·파라미터 누락·타입 불일치·405·없는 경로) → C400·C404·C405 / 그 외 → 내부 메시지를 숨기고 C500 |
| 기동 안내 `StartupLogPrinter` | `core.startup-log.enabled=true` | 앱이 뜨면 CORE 로고, Local·Network 주소, 붙인 모듈의 초기화 결과(`[core] TossPayUtils 미초기화 — ...`)를 한 번 찍는다 |

## API 요약
| 클래스 | 내용 |
|---|---|
| `ApiResponse<T>` | `success(data)` · `success()` · `error(code, message)` — JSON `success`·`data`·`code`·`message` |
| `CoreException` | `(ErrorCode)` · `(ErrorCode, detail)` · `(ErrorCode, detail, cause)` · `addErrorLogData(key, value)` |
| `ErrorCode` | `code()` · `message()` · `status()` · `ErrorCode.of(code, message, status)` |
| `ValidateUtils` | `validate(target)` |
| `PageResponse<T>` | `of(content, page, size, total)` · `slice(all, page, size)` · `map(mapper)` — page 는 0 부터 |
| `PageUtils` | `from(Page)` · `pageable(page, size, sort)` (size 1~100 검증, C400) — spring-data-commons 가 있는 앱에서 |
| `UrlUtils` | `isHttpsOrLoopback(url)` — 벤더 주소는 https, 로컬 테스트 서버만 http 허용 |
| `StartupReporter` / `StartupReport` | 모듈이 기동 안내에 자기 상태를 알리는 확장 지점 — `ready` · `notReady` · `link` |

## 에러코드
| 코드 | 상태 | 메시지 |
|---|---|---|
| C400 | 400 | 잘못된 요청입니다. |
| C401 | 401 | 인증이 필요합니다. |
| C403 | 403 | 권한이 없습니다. |
| C404 | 404 | 대상을 찾을 수 없습니다. |
| C500 | 500 | 서버 오류가 발생했습니다. |

## 주의
- 예외 메시지(detail)는 그대로 응답에 나간다 — 개인정보·내부 정보는 `addErrorLogData` 로만.
- 직접 `CoreExceptionHandler` 빈을 만들면 core 것은 물러난다.

## 예제
[`example/common`](../../example/common) — 상품 등록·조회·구매 (검증 400·품절 409·미처리 500)
