# file

파일 업로드·다운로드·삭제·URL — 로컬 디스크 또는 S3. 크기·확장자·경로 검증을 저장소가 직접 한다.

## 의존성
```groovy
implementation 'com.corelib:file:0.2.0'   // 저장소 설정은 루트 README "설치"
implementation 'software.amazon.awssdk:s3:2.55.13'   // S3 를 쓸 때만
```
비웹 앱(spring-web 이 없는 배치·워커 등)은 지원하지 않는다 — `FileStorage`·`FileUtils` 시그니처가 `MultipartFile` 을 쓴다.

## 설정
```yaml
core:
  file:
    storage: local                   # local(기본) · s3
    max-size: 10MB                   # 기본 10MB
    allowed-extensions: [png, jpg, pdf]   # 비우면 위험 확장자만 막고 나머지 허용 (기동 안내에 경고 — 지정 권장)
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
- 확장자는 영문·숫자 1~10자만 받는다 — `x.txt:evil`(NTFS ADS)·개행·공백·기호가 섞이면 차단.
- 서버·OS 실행 확장자는 `allowed-extensions` 에 적어도 항상 차단(대소문자·이중 확장자 포함) — jsp 계열(jspx·jspa 등)·php 계열(php7·php8·phtml·pht·phar 등)·asp 계열(aspx·asmx·ashx 등)·SSI(shtml)·exe·bat·sh·jar·hta·ps1·psm1·vbs·vbe·wsf·scr 등.
- html·htm·xhtml·xht·svg·svgz·js·mjs 는 `allowed-extensions` 에 적었을 때만 허용.
- `max-size` 초과·빈 파일·파일명 없음·확장자 없는 파일 차단. InputStream 업로드는 신고 크기를 믿지 않고 실제로 읽은 바이트를 세어 `max-size` 를 넘는 순간 중단한다 (로컬은 쓰다 만 파일을 지운다).
- 저장 Content-Type 은 확장자로 서버가 정한다 — 클라이언트가 보낸 값은 쓰지 않는다. png·jpg/jpeg·gif·webp·bmp·pdf·txt·csv·xls/xlsx·doc/docx·ppt/pptx·zip·mp3·mp4 만 매핑하고 나머지(허용한 html·svg 포함)는 `application/octet-stream`. `StoredFile.contentType` 도 이 값이다.

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| FILE-INVALID | 400 | 크기 초과·금지 확장자·잘못된 디렉터리명·경로 탈출 |
| FILE-NOT-FOUND | 404 | 없는 키 |
| FILE-STORAGE | 500 | 디스크·S3 오류 |

## 주의
- 다운로드·삭제는 **우리가 발급한 키**만 받는다 — 사용자가 넣은 임의 키로 남의 파일에 접근하지 못하게, 소유자 확인은 소비 앱이 한다.
- 다운로드 응답은 `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff` 로 내려 브라우저 실행을 막는다 (예제 참고). S3 presigned URL 은 `response-content-disposition=attachment` 가 붙어 있다.

## 예제
[`example/file`](../../example/file) — 업로드·목록·다운로드·삭제 화면
