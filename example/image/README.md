# example-image

`modules/image` 로 만든 **프로필 사진** 예제 — 업로드 → 썸네일·중간·큰 사이즈 3종, 규격별 보기.

## 실행
```bash
./gradlew :example:image:bootRun
```
화면: http://localhost:8117

| API | 내용 |
|---|---|
| `POST /api/profile-images/upload` (multipart `file`) | 원본 정보 + 규격 3종 (thumbnail 100 정사각 · medium 400 · large 1080) |
| `GET /api/profile-images/view?imageId=...&size=thumbnail` | 규격별 이미지 (캐시 30일, nosniff) |

## 처리 (`service/ProfileImageExampleService`)
- 업로드 파일의 Content-Type·확장자는 믿지 않는다 — `ImageUtils.info` 로 실제 이미지인지 확인 (`.png` 로 바꾼 PHP 는 `IMAGE-INVALID`)
- 해상도 상한(`core.image.max-pixels`)을 넘으면 디코딩 전에 `IMAGE-TOO-LARGE`
- `ImageUtils.variants` 로 한 번 디코딩해 3종 — 휴대폰 사진 회전 보정, EXIF(GPS) 제거
- 원본은 저장하지 않는다 (위치정보가 든 원본을 남기지 않게)

## 소비 프로젝트에 옮길 것
- `service/ProfileImageExampleService` — 저장소를 메모리 대신 `FileUtils.upload`(S3)로, 키는 이미지 테이블에
