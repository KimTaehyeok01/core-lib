# excel

엑셀(xlsx)·CSV 쓰기/읽기 — DTO 필드에 `@ExcelColumn` 을 붙이면 다운로드와 업로드에 같이 쓴다.
xlsx 는 Apache POI 5.5.1, CSV 는 직접 구현(RFC 4180).

## 의존성
```groovy
implementation 'com.corelib:excel:0.1.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
core:
  excel:
    max-rows: 10000          # 읽기 행 수 상한 (기본 10,000)
spring:
  servlet:
    multipart:
      max-file-size: 5MB     # 업로드 크기 상한은 서블릿 설정으로
```

## 사용법
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // 읽기에는 기본 생성자가 필요 (private 도 됨)
public class MemberRow {
    @ExcelColumn(header = "이름", order = 1, required = true)
    private String name;
    @ExcelColumn(header = "포인트", order = 2, format = "#,##0")
    private Long point;
    @ExcelColumn(header = "가입일", order = 3)
    private LocalDate joinedDt;
}

byte[] xlsx = ExcelUtils.write(rows, MemberRow.class, "회원");       // 다운로드
byte[] form = ExcelUtils.template(MemberRow.class, "회원등록");       // 빈 양식
byte[] csv  = CsvUtils.write(rows, MemberRow.class);                  // UTF-8 BOM

ExcelDto.ReadResult<MemberRow> result = ExcelUtils.read(file.getInputStream(), MemberRow.class);   // 또는 CsvUtils.read
if (!result.isSuccess()) {
    result.getErrors();   // [{rowNumber: 3, column: "가입일", message: "날짜 형식이 아닙니다."}, ...]
}
```

## 동작
| 항목 | 내용 |
|---|---|
| 지원 타입 | String · Integer/int · Long/long · Double/double · BigDecimal · Boolean/boolean(Y/N·예/아니오·true/false) · LocalDate · LocalDateTime · enum(이름) |
| 쓰기 | 1행 헤더(굵게·고정), 열 너비·서식, xlsx 는 스트리밍(SXSSF) — 메모리에 100행만 |
| 읽기 | **헤더 이름으로** 열을 찾는다(순서 무관). 빈 행 건너뜀. 형식 오류는 행·열별로 모아 `errors`, 맞게 읽힌 행은 `rows`(+ `rowNumbers`) |
| CSV | UTF-8 + BOM(엑셀에서 한글 안 깨짐), 따옴표 안 쉼표·줄바꿈·`""` 처리 |
| 수식 주입 | CSV 에서 `=` `+` `-` `@` 탭·CR 로 시작하는 값은 앞에 `'` — 엑셀이 `=HYPERLINK(...)` 등을 실행하지 않게. 다시 읽으면 원래 값. xlsx 는 글자 칸이라 실행되지 않는다 |

## API 요약
| 클래스 | 메서드 |
|---|---|
| `ExcelUtils` | `write(rows, type, sheetName)` · `template(type, sheetName)` · `read(inputStream, type)` · `CONTENT_TYPE` |
| `CsvUtils` | `write(rows, type)` · `read(inputStream, type)` · `CONTENT_TYPE` |
| `ExcelDto.ReadResult<T>` | `rows` · `rowNumbers` · `errors` · `totalRows` · `isSuccess()` |
| `@ExcelColumn` | `header` · `order` · `width` · `format` · `required` |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| EXCEL-INVALID | 400 | xlsx·csv 아님, 헤더 없음, 필수 열 없음, 행 수 초과, 닫히지 않은 따옴표 |
| EXCEL-MAPPING | 500 | DTO 정의 오류 (기본 생성자 없음·지원하지 않는 타입·@ExcelColumn 없음) |

## 주의
- 오류 메시지에는 입력값 원문을 넣지 않는다 (생년월일 같은 개인정보일 수 있다).
- 전부 받을지, 하나라도 틀리면 거절할지는 호출측이 정한다 — 예제는 "하나라도 틀리면 아무것도 등록 안 함".
- 명단 다운로드는 개인정보 — 관리자만, 다운로드 이력을 남긴다.

## 예제
[`example/excel`](../../example/excel) — 회원 명단 다운로드·양식·일괄 등록(형식·중복 오류를 행 번호로)·목록 페이지
