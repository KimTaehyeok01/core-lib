# example-excel

`modules/excel` 로 만든 **회원 명단** 예제 — 엑셀·CSV 다운로드, 등록 양식, 일괄 등록, 목록 페이지.

## 실행
```bash
./gradlew :example:excel:bootRun
```
화면: http://localhost:8116

| API | 내용 |
|---|---|
| `GET /api/members/list?page=0&size=10` | `PageResponse` — 이름·이메일 마스킹 |
| `GET /api/members/export?format=xlsx\|csv` | 전체 명단 (한글 파일명, attachment + nosniff) |
| `GET /api/members/template` | 헤더만 있는 등록 양식 |
| `POST /api/members/import` (multipart `file`) | xlsx·csv 일괄 등록 |

## 일괄 등록 규칙 (`service/MemberExcelExampleService`)
1. 형식 검사 — excel 모듈 (`이름`·`이메일` 필수, 숫자·날짜·등급 형식)
2. 업무 검사 — 이메일 형식, 이미 가입된 이메일, 파일 안 중복 (`rowNumbers` 로 행 번호를 알린다)
3. **하나라도 틀리면 아무것도 등록하지 않는다** — 오류 목록(`3행 [가입일] 날짜 형식이 아닙니다.`)을 보고 고쳐 다시 올린다
4. 성공하면 등록 건수와 평균 포인트(`RoundUtils`)

## 소비 프로젝트에 옮길 것
- `dto/MemberExcelExampleDto.MemberRow` 처럼 다운로드·업로드가 같은 DTO 를 쓰게
- 명단 다운로드는 관리자만, 다운로드 이력 기록 (개인정보)
