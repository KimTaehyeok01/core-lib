# file

파일 업로드·다운로드·삭제·URL — 로컬 디스크 또는 S3. 크기·확장자·경로 검증을 저장소가 직접 한다.

## 의존성
```groovy
implementation 'com.corelib:file:0.1.0'   // 저장소 설정은 루트 README "설치"
implementation 'software.amazon.awssdk:s3:2.55.13'   // S3 를 쓸 때만
```

## 설정
```yaml
core:
  file:
    storage: local                   # local(기본) · s3
    max-size: 10MB                   # 기본 10MB
    allowed-extensions: [png, jpg, pdf]   # 비우면 위험 확장자만 막고 나머지 허용
    local:
      base-dir: ./uploads            # 기본 ./uploads
      url-prefix: /files             # getUrl 앞에 붙는 경로 — 실제 서빙은 소비 앱이 정한다
    s3:
      bucket: my-bucket
      region: ap-northeast-2         # 기본
      endpoint:                      # S3 호환 저장소(MinIO·NCP 등)일 때만
      presigned-url-expiry: 10m      # 기본 10분
```
S3 자격증명은 AWS 기본 체인(환경변수·프로필·IAM Role) — 키를 설정 파일에 두지 않는다.

## 사용법
```java
FileDto.StoredFile stored = FileUtils.upload("profile", multipartFile);   // key = profile/2026/10/24/{uuid}.png
// DB 에는 stored.getKey() 만 저장

InputStream in = FileUtils.download(key);
String url = FileUtils.getUrl(key);     // local: /files/{key}, s3: 만료되는 presigned URL
FileUtils.delete(key);
```

## API 요약
| 클래스 | 내용 |
|---|---|
| `FileUtils` | `upload(directory, MultipartFile)` · `upload(directory, originalName, InputStream, size, contentType)` · `download(key)` · `delete(key)` · `getUrl(key)` |
| `FileDto.StoredFile` | `key` · `originalName` · `size` · `contentType` · `url` |
| `FileStorage` | 저장소 인터페이스 — 직접 빈을 만들면 그 저장소를 쓴다 |

## 검증 (항상 적용)
- 파일명은 저장하지 않고 `{디렉터리}/{yyyy/MM/dd}/{UUID}.{확장자}` 로 새로 만든다. 디렉터리명은 영문·숫자·`-`·`_`·`/` 만.
- 경로 탈출(`../`·절대경로·역슬래시) 차단 — 키 검사 + 실제 경로가 base-dir 안인지 이중 확인.
- 웹셸 확장자(jsp·php·asp 등)는 항상 차단(대소문자·이중 확장자 포함). html·svg·js 는 `allowed-extensions` 에 적었을 때만 허용.
- `max-size` 초과·빈 파일·파일명 없음·확장자 없는 파일 차단.

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| FILE-INVALID | 400 | 크기 초과·금지 확장자·잘못된 디렉터리명·경로 탈출 |
| FILE-NOT-FOUND | 404 | 없는 키 |
| FILE-STORAGE | 500 | 디스크·S3 오류 |

## 주의
- 다운로드·삭제는 **우리가 발급한 키**만 받는다 — 사용자가 넣은 임의 키로 남의 파일에 접근하지 못하게, 소유자 확인은 소비 앱이 한다.
- 다운로드 응답은 `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff` 로 내려 브라우저 실행을 막는다 (예제 참고).

## 예제
[`example/file`](../../example/file) — 업로드·목록·다운로드·삭제 화면
