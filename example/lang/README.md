# example-lang

`modules/lang` 으로 만든 **비회원 게시판** 예제 — 글 등록·목록·상세·삭제.

## 실행
```bash
./gradlew :example:lang:bootRun
```
```bash
curl -X POST localhost:8101/api/posts/create -H 'Content-Type: application/json' \
  -d '{"title":"  첫 글 ","content":"내용","authorName":"홍길동","authorEmail":"hong@example.com","tags":[" #Java","java","Spring"]}'
curl 'localhost:8101/api/posts/list?tag=java'
curl 'localhost:8101/api/posts/detail?shareCode={shareCode}'
curl -X POST localhost:8101/api/posts/delete -H 'Content-Type: application/json' -d '{"shareCode":"{shareCode}","deletePin":"{deletePin}"}'
```

## 서비스가 쓰는 lang 유틸
| 유틸 | 쓰는 곳 |
|---|---|
| `StringUtils.trimToNull / defaultIfBlank` | 작성자 공백 정리, 비우면 "익명" |
| `StringUtils.truncate` | 목록 요약 50자, 태그 20자 상한 |
| `CollectionUtils.emptyIfNull` | 태그 목록이 null 이어도 그대로 순회 |
| `RandomStringUtils` | 공유 코드 8자리(순번 노출 방지), 삭제 PIN 6자리 — SecureRandom |
| `MaskingUtils.name / email` | 상세·목록의 작성자 이름·이메일 |

## 소비 프로젝트에 옮길 것
- `service/PostExampleService` — 글 테이블만 DB 로 바꾸면 된다
- 외부에 노출되는 식별자는 순번 대신 추측 불가 코드, PIN 비교는 상수 시간(`MessageDigest.isEqual`)
