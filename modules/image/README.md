# image

이미지 변환 — byte[] 를 받아 byte[] 를 돌려준다. 리사이즈·가운데 크롭·여러 규격 한 번에·휴대폰 사진 회전 보정·webp 읽기.
JDK ImageIO 로 처리하고, webp 읽기만 TwelveMonkeys `imageio-webp` 3.15.3 플러그인을 쓴다.

## 의존성
```groovy
implementation 'com.corelib:image:0.2.0'   // 저장소 설정은 루트 README "설치"
```

## 설정
```yaml
core:
  image:
    max-pixels: 40000000    # 가로×세로 상한 (기본 4천만 ≈ 8000×5000) — 넘으면 디코딩 전에 거절
```

## 사용법
```java
ImageDto.Info info = ImageUtils.info(bytes);    // 디코딩 없이 크기·형식 — 업로드 직후 "진짜 이미지인지" 확인

List<ImageDto.Result> results = ImageUtils.variants(bytes, List.of(
    ImageDto.Spec.builder().name("thumbnail").width(100).height(100).mode(ImageDto.Mode.COVER).build(),   // 정사각 썸네일
    ImageDto.Spec.builder().name("medium").width(400).height(400).build(),                                // 상자 안에 맞춤
    ImageDto.Spec.builder().name("logo").width(200).height(200).format(ImageDto.Format.PNG).build()));    // 투명 유지

ImageDto.Result small = ImageUtils.resize(bytes, 800, 800, ImageDto.Format.JPEG);
ImageDto.Result square = ImageUtils.cropCenter(bytes, 300, 300, ImageDto.Format.JPEG);
// result.getBytes() 를 FileUtils.upload(...) 로 저장
```

## 동작
| 항목 | 내용 |
|---|---|
| 읽기 / 쓰기 | jpeg·png·gif(첫 프레임)·bmp·webp / **jpeg·png** |
| `FIT` | 상자 안에 들어가게 비율 유지 축소 — 결과가 상자보다 작을 수 있다 |
| `COVER` | 상자를 꽉 채우고 넘치는 쪽을 가운데 기준으로 잘라 정확한 크기 |
| 확대 | 하지 않는다 — 원본이 작으면 원본 크기 (COVER 는 같은 비율로 가운데만 자른다) |
| 화질 | 크게 줄일 때는 반씩 여러 번 줄여 계단·뭉개짐을 줄인다. JPEG 품질 기본 0.85 |
| 투명 | PNG 는 유지, JPEG 은 흰 배경 위에 (안 하면 검게 나온다) |
| EXIF 방향 | 휴대폰 사진의 방향값(1~8)을 읽어 바로 세운다 |
| 메타데이터 | 다시 인코딩하므로 결과에는 EXIF(GPS 위치 등)가 없다 |
| 디코딩 | 모든 규격이 원본의 절반 이하면 건너뛰어 읽는다(jpeg·png·gif·bmp, 가장 큰 규격의 2배 해상도는 남김). 결과 크기는 원본 기준과 같다. webp 는 원본 해상도로 읽는다 |
| ImageIO 전역 설정 | 바꾸지 않는다 — 입출력은 메모리 스트림을 직접 써서 임시 파일을 만들지 않는다. 플러그인 등록은 기동 시 `ImageInitialize` 가 1회 |

## 메모리와 동시 처리
디코딩한 픽셀은 모두 힙에 올라간다. 요청당 최대치는 `max-pixels` 로 정해진다.

| 경우 | 요청당 힙 (기본 `max-pixels` 4천만) |
|---|---|
| 원본 해상도로 디코딩 (규격 하나라도 원본의 절반 초과, webp) | 가로×세로×3~4byte ≈ **120~160MB** |
| + JPEG EXIF 회전(방향값 2~8) | 같은 크기 사본이 하나 더 ≈ **240~320MB** |
| 건너뛰어 읽기 (예: 8000×5000 → 100·400·1080 규격, 간격 3) | 1/9 ≈ **15~20MB** |

축소 중간 버퍼(첫 단계가 원본의 1/4)와 업로드 바이트(`max-file-size`)는 위에 더해진다.

- 동시 처리 수는 `(힙 여유) ÷ (요청당 최대)` 이하로 묶는다. 예: `-Xmx2g`, 여유 1GB, 최악 320MB → 동시 3건. 호출부를 `Semaphore` 나 전용 스레드풀(고정 크기)로 감싼다.
- 휴대폰 사진(최대 약 6000×4000)만 받으면 `max-pixels: 25000000` 정도로 낮추면 최악치가 약 60% 로 줄어든다.
- 큰 규격(원본 크기 보관 등)을 같이 만들면 건너뛰어 읽지 못하니 위 "원본 해상도" 기준으로 잡는다.

## API 요약
| 메서드 | 반환 |
|---|---|
| `info(bytes)` | `Info` — `width` · `height` · `format` |
| `resize(bytes, maxWidth, maxHeight, format)` · `cropCenter(bytes, width, height, format)` | `Result` |
| `variants(bytes, specs)` | `List<Result>` — 원본 한 번만 디코딩 |
| `readableFormats()` | 이 런타임에서 실제로 읽을 수 있는 형식 (webp 플러그인 확인용) |
| `ImageDto.Spec` | `name` · `width` · `height` · `mode`(FIT 기본) · `format`(JPEG 기본) · `quality` |
| `ImageDto.Result` | `name` · `bytes` · `width` · `height` · `format`(`getContentType()`) |

## 에러코드
| 코드 | 상태 | 언제 |
|---|---|---|
| IMAGE-INVALID | 400 | 이미지 아님(확장자만 바꾼 파일 포함)·지원 안 하는 형식·손상·빈 값 |
| IMAGE-TOO-LARGE | 400 | 가로×세로가 `max-pixels` 초과 — 작은 파일이 거대한 해상도로 풀리는 공격 방지 |
| IMAGE-PROCESS | 500 | 인코딩 실패 |

## 주의
- 업로드 파일의 Content-Type·확장자는 믿지 말고 `info()` 로 확인한다.
- 원본(위치정보 포함)을 저장할지는 서비스 정책 — 예제는 변환본만 저장한다.
- 파일 크기 상한은 서블릿 설정(`spring.servlet.multipart.max-file-size`)으로 함께 둔다.

## 예제
[`example/image`](../../example/image) — 프로필 사진 업로드 → 썸네일·중간·큰 사이즈, 규격별 보기
