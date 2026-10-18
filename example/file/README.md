# example-file

`modules/file` 사용 예제 — 업로드·목록·다운로드·삭제. 기본은 로컬 디스크, 설정만 바꾸면 S3.

## 실행
```bash
./gradlew :example:file:bootRun
```
화면: http://localhost:8107 — 파일은 `example/file/uploads/` 에 저장된다(`FILE_BASE_DIR` 로 변경).

## S3 로 바꾸기
1. `build.gradle` 에 `implementation 'software.amazon.awssdk:s3:2.55.13'`
2. `application.yml` 에 `core.file.storage: s3`, `core.file.s3.bucket`
3. 자격증명은 AWS 기본 체인(환경변수 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, 프로필, IAM Role) — yml 에 적지 않는다

## 소비 프로젝트에 옮길 것
- `service/FileExampleService` — `FileUtils.upload(디렉터리, file)`, DB 에는 `key` 만 저장
- 다운로드·삭제는 **우리가 발급한 키**만 받는다 + 소유자/권한 확인
- `controller/FileExampleController#download` — `attachment` + `filename*`(한글) + `octet-stream` + `nosniff`
- `spring.servlet.multipart.max-file-size` 를 `core.file.max-size` 와 맞춘다
