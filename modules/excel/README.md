# excel

엑셀(xlsx)·CSV 쓰기/읽기 — DTO 필드에 `@ExcelColumn` 을 붙이면 다운로드와 업로드에 같이 쓴다.
xlsx 는 Apache POI 5.5.1, CSV 는 직접 구현(RFC 4180).

## 의존성
```groovy
implementation 'com.corelib:excel:0.2.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
core:
  excel:
    max-rows: 10000          # 읽기 행 수 상한 (기본 10,000)
spring:
  servlet:
    multipart:
      max-file-size: 5MB     # 필수 — 업로드 크기 상한은 이 모듈이 아니라 호출측(서블릿 multipart) 설정이다
      max-request-size: 5MB
```
업로드 크기 상한은 **반드시 호출측에서** 건다. 이 모듈은 행 수·한 행 길이·압축 비율만 막는다 (xlsx 는 업로드를 임시 파일에 두고 읽는다).

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

// 대량 다운로드 — byte[] 사본 없이 응답 스트림에 바로 (rows 는 Iterable, out 은 닫지 않는다)
ExcelUtils.write(rows, MemberRow.class, "회원", response.getOutputStream());
CsvUtils.write(rows, MemberRow.class, response.getOutputStream());

ExcelDto.ReadResult<MemberRow> result = ExcelUtils.read(file.getInputStream(), MemberRow.class);   // 또는 CsvUtils.read
if (!result.isSuccess()) {
    result.getErrors();   // [{rowNumber: 3, column: "가입일", message: "날짜 형식이 아닙니다."}, ...]
}
```

## 동작
| 항목 | 내용 |
|---|---|
| 지원 타입 | String · Integer/int · Long/long · Double/double · BigDecimal · Boolean/boolean(Y/N·예/아니오·true/false) · LocalDate · LocalDateTime · enum(이름) |
| 쓰기 | 1행 헤더(굵게·고정), 열 너비·서식, xlsx 는 스트리밍(SXSSF) — 메모리에 100행만. `OutputStream` 오버로드는 byte[] 사본 없이 바로 쓴다 |
| 긴 숫자 쓰기 | 엑셀 숫자는 15자리까지만 정확하다 — Long·BigDecimal 이 15자리를 넘으면 **글자 칸**으로 쓴다 (다시 읽으면 원래 값) |
| 읽기 | **헤더 이름으로** 열을 찾는다(순서 무관). 빈 행 건너뜀. 형식 오류는 행·열별로 모아 `errors`, 맞게 읽힌 행은 `rows`(+ `rowNumbers`) |
| 읽기 상한 | xlsx 는 SAX 스트리밍 — 데이터 행이 `max-rows` 를 넘는 순간 멈춘다. 빈 행·서식만 있는 행도 행 번호가 `max-rows×2+100` 을 넘으면 멈춘다. CSV 도 같은 기준 + 한 행 64K자 |
| 압축 폭탄 | 기동 시 `ZipSecureFile` 압축 해제 비율 1%(100배)·항목 100MB 상한을 명시 — **POI 전역 설정**이라 앱의 다른 POI 사용에도 적용된다. 중앙 디렉터리가 깨진 zip 은 거부 |
| 숫자 칸 → String | 지수 표기·`.0` 없이 읽는다 (`1.01E+10` → `10123456780`). **선행 0 은 숫자 칸에 남지 않는다** — `010…` 휴대폰·우편번호 열은 엑셀에서 **텍스트 서식**이어야 한다 (빈 양식에 그냥 입력하면 엑셀이 숫자로 바꾼다) |
| BigDecimal | 유효 자릿수·소수 자릿수 38 상한 — 넘으면(`1E999999999` 등) 그 칸 오류 "숫자 범위를 벗어났습니다." |
| CSV | UTF-8 + BOM(엑셀에서 한글 안 깨짐), 따옴표 안 쉼표·줄바꿈·`""` 처리 |
| 수식 주입 | CSV 의 **String 열**에서 `=` `+` `-` `@` 탭·CR 로 시작하는 값은 앞에 `'` — 엑셀이 `=HYPERLINK(...)` 등을 실행하지 않게. 다시 읽으면 원래 값. 숫자·날짜 열은 그대로(`-5` 는 `-5`). xlsx 는 글자 칸이라 실행되지 않는다 |

## API 요약
| 클래스 | 메서드 |
|---|---|
| `ExcelUtils` | `write(rows, type, sheetName)` · `write(rows, type, sheetName, outputStream)` · `template(type, sheetName)` · `read(inputStream, type)` · `CONTENT_TYPE` |
| `CsvUtils` | `write(rows, type)` · `write(rows, type, outputStream)` · `read(inputStream, type)` · `CONTENT_TYPE` |
| `ExcelDto.ReadResult<T>` | `rows` · `rowNumbers` · `errors` · `totalRows` · `isSuccess()` |
| `@ExcelColumn` | `header` · `order` · `width` · `format` · `required` |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| EXCEL-INVALID | 400 | xlsx·csv 아님·손상, 헤더 없음, 필수 열 없음, 행 수 초과(빈 행 포함 상한 포함), 한 행 64K자 초과, 닫히지 않은 따옴표, 압축 폭탄 |
| EXCEL-MAPPING | 500 | DTO 정의 오류 (기본 생성자 없음·지원하지 않는 타입·@ExcelColumn 없음), 쓰기 실패 |

## 주의
- 오류 메시지에는 입력값 원문을 넣지 않는다 (생년월일 같은 개인정보일 수 있다).
- 전부 받을지, 하나라도 틀리면 거절할지는 호출측이 정한다 — 예제는 "하나라도 틀리면 아무것도 등록 안 함".
- 명단 다운로드는 개인정보 — 관리자만, 다운로드 이력을 남긴다.

## 예제
[`example/excel`](../../example/excel) — 회원 명단 다운로드·양식·일괄 등록(형식·중복 오류를 행 번호로)·목록 페이지
