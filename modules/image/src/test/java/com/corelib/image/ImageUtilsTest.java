package com.corelib.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageUtilsTest {

	/** 1×1 무손실 webp */
	private static final byte[] WEBP = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

	@AfterEach
	void reset() {
		ImageUtils.initialize(40_000_000L);
	}

	/** 왼쪽 절반 빨강, 오른쪽 절반 파랑 */
	private static BufferedImage halves(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.RED);
		graphics.fillRect(0, 0, width / 2, height);
		graphics.setColor(Color.BLUE);
		graphics.fillRect(width / 2, 0, width - width / 2, height);
		graphics.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	private static BufferedImage decode(byte[] bytes) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	private static boolean isRed(int rgb) {
		Color color = new Color(rgb, true);
		return color.getRed() > 180 && color.getBlue() < 80;
	}

	private static boolean isBlue(int rgb) {
		Color color = new Color(rgb, true);
		return color.getBlue() > 180 && color.getRed() < 80;
	}

	/** SOI 바로 뒤에 Orientation 하나만 든 EXIF(APP1, 빅엔디언) 를 끼운다 — 휴대폰 사진 흉내 */
	private static byte[] withOrientation(byte[] jpeg, int orientation) {
		byte[] app1 = {
			(byte) 0xFF, (byte) 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0x00, 0x00,
			'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,             // TIFF 헤더, IFD0 오프셋 8
			0x00, 0x01,                                                // 항목 1개
			0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,             // 0x0112 Orientation, SHORT, 1개
			0x00, (byte) orientation, 0x00, 0x00,                       // 값
			0x00, 0x00, 0x00, 0x00                                      // 다음 IFD 없음
		};
		byte[] result = new byte[jpeg.length + app1.length];
		System.arraycopy(jpeg, 0, result, 0, 2);
		System.arraycopy(app1, 0, result, 2, app1.length);
		System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
		return result;
	}

	@Test
	@DisplayName("info — 디코딩 없이 크기·형식")
	void info() throws Exception {
		ImageDto.Info info = ImageUtils.info(encode(halves(400, 200), "jpg"));

		assertThat(info.getWidth()).isEqualTo(400);
		assertThat(info.getHeight()).isEqualTo(200);
		assertThat(info.getFormat()).isEqualTo("jpeg");
	}

	@Test
	@DisplayName("FIT — 비율 유지 축소, 원본보다 큰 상자면 확대하지 않음")
	void fit() throws Exception {
		byte[] source = encode(halves(400, 200), "png");

		ImageDto.Result small = ImageUtils.resize(source, 100, 100, ImageDto.Format.JPEG);
		assertThat(small.getWidth()).isEqualTo(100);
		assertThat(small.getHeight()).isEqualTo(50);
		assertThat(decode(small.getBytes()).getWidth()).isEqualTo(100);

		ImageDto.Result big = ImageUtils.resize(source, 2000, 2000, ImageDto.Format.PNG);
		assertThat(big.getWidth()).isEqualTo(400);
		assertThat(big.getHeight()).isEqualTo(200);
	}

	@Test
	@DisplayName("COVER — 정확한 크기로 가운데를 잘라냄 (가로 400×200 → 정사각 100, 왼쪽 빨강·오른쪽 파랑 유지)")
	void cover() throws Exception {
		ImageDto.Result square = ImageUtils.cropCenter(encode(halves(400, 200), "png"), 100, 100, ImageDto.Format.PNG);
		BufferedImage image = decode(square.getBytes());

		assertThat(image.getWidth()).isEqualTo(100);
		assertThat(image.getHeight()).isEqualTo(100);
		assertThat(isRed(image.getRGB(10, 50))).isTrue();
		assertThat(isBlue(image.getRGB(90, 50))).isTrue();
	}

	@Test
	@DisplayName("다중 규격 — 한 번 디코딩으로 이름별 결과, PNG 는 투명 유지·JPEG 는 흰 배경")
	void variants() throws Exception {
		BufferedImage transparent = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
		List<ImageDto.Result> results = ImageUtils.variants(encode(transparent, "png"), List.of(
			ImageDto.Spec.builder().name("thumbnail").width(64).height(64).mode(ImageDto.Mode.COVER).format(ImageDto.Format.PNG).build(),
			ImageDto.Spec.builder().name("medium").width(150).height(150).format(ImageDto.Format.JPEG).quality(0.7f).build()));

		assertThat(results).extracting(ImageDto.Result::getName).containsExactly("thumbnail", "medium");
		assertThat(new Color(decode(results.get(0).getBytes()).getRGB(5, 5), true).getAlpha()).isZero();
		assertThat(new Color(decode(results.get(1).getBytes()).getRGB(5, 5)).getRed()).isGreaterThan(240);
		assertThat(results.get(1).getFormat().getContentType()).isEqualTo("image/jpeg");
	}

	@Test
	@DisplayName("EXIF 방향 6(시계 90°) — 가로로 저장된 휴대폰 세로 사진을 바로 세움 (왼쪽 빨강 → 위쪽 빨강)")
	void exifRotate() throws Exception {
		byte[] phonePhoto = withOrientation(encode(halves(400, 200), "jpg"), 6);
		assertThat(ExifOrientation.read(phonePhoto)).isEqualTo(6);

		ImageDto.Result upright = ImageUtils.resize(phonePhoto, 1000, 1000, ImageDto.Format.PNG);
		BufferedImage image = decode(upright.getBytes());

		assertThat(image.getWidth()).isEqualTo(200);
		assertThat(image.getHeight()).isEqualTo(400);
		assertThat(isRed(image.getRGB(100, 50))).isTrue();
		assertThat(isBlue(image.getRGB(100, 350))).isTrue();
	}

	@Test
	@DisplayName("EXIF 방향 3·8 과 깨진 EXIF — 180° 는 크기 유지, 8 은 세로로, 길이가 엉터리면 무시")
	void exifOthers() throws Exception {
		byte[] jpeg = encode(halves(400, 200), "jpg");
		BufferedImage rotated180 = decode(ImageUtils.resize(withOrientation(jpeg, 3), 1000, 1000, ImageDto.Format.PNG).getBytes());
		assertThat(isBlue(rotated180.getRGB(50, 100))).isTrue();    // 왼쪽이 파랑으로
		assertThat(ImageUtils.resize(withOrientation(jpeg, 8), 1000, 1000, ImageDto.Format.PNG).getHeight()).isEqualTo(400);

		byte[] broken = withOrientation(jpeg, 6);
		broken[4] = (byte) 0x7F;    // APP1 길이를 파일보다 크게
		assertThat(ExifOrientation.read(broken)).isEqualTo(ExifOrientation.NORMAL);
	}

	@Test
	@DisplayName("해상도 폭탄 — 픽셀 상한을 넘으면 디코딩 전에 IMAGE-TOO-LARGE")
	void tooLarge() throws Exception {
		ImageUtils.initialize(1_000);

		assertThatThrownBy(() -> ImageUtils.resize(encode(halves(400, 200), "png"), 10, 10, ImageDto.Format.PNG))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ImageError.TOO_LARGE);
	}

	@Test
	@DisplayName("이미지 아님·빈 값 — IMAGE-INVALID")
	void invalid() {
		assertThatThrownBy(() -> ImageUtils.info("not an image".getBytes()))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ImageError.INVALID_IMAGE);
		assertThatThrownBy(() -> ImageUtils.resize(new byte[0], 10, 10, ImageDto.Format.PNG))
			.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ImageError.INVALID_IMAGE);
	}

	@Test
	@DisplayName("webp 읽기 — 플러그인으로 읽어서 PNG 로")
	void webp() {
		assertThat(ImageUtils.readableFormats()).contains("webp", "jpeg", "png");
		assertThat(ImageUtils.info(WEBP).getFormat()).isEqualTo("webp");
		assertThat(ImageUtils.resize(WEBP, 10, 10, ImageDto.Format.PNG).getWidth()).isEqualTo(1);
	}

	@Test
	@DisplayName("ImageIO 전역 설정 불변 — 디스크 캐시가 켜져 있고 캐시 폴더가 없어도 메모리 스트림으로 처리, useCache 는 그대로")
	void keepsImageIoGlobals(@TempDir Path tempDir) throws Exception {
		byte[] source = encode(halves(400, 200), "jpg");
		File cacheDirectory = Files.createDirectory(tempDir.resolve("cache")).toFile();
		boolean useCache = ImageIO.getUseCache();
		ImageIO.setUseCache(true);
		ImageIO.setCacheDirectory(cacheDirectory);
		Files.delete(cacheDirectory.toPath());    // ImageIO 가 만든 스트림이면 임시 파일을 못 만들어 실패한다
		try {
			assertThat(ImageUtils.info(source).getWidth()).isEqualTo(400);
			assertThat(ImageUtils.resize(source, 100, 100, ImageDto.Format.JPEG).getWidth()).isEqualTo(100);
			assertThat(ImageUtils.resize(source, 100, 100, ImageDto.Format.PNG).getHeight()).isEqualTo(50);
			assertThat(ImageIO.getUseCache()).isTrue();
		} finally {
			ImageIO.setCacheDirectory(null);
			ImageIO.setUseCache(useCache);
		}
	}

	@Test
	@DisplayName("서브샘플링 간격 — 가장 큰 규격의 2배 해상도는 남기고, 절반 이상을 쓰는 규격이 있으면 건너뛰지 않음")
	void subsamplingStep() {
		ImageDto.Spec thumbnail = ImageDto.Spec.builder().name("thumbnail").width(64).height(64).mode(ImageDto.Mode.COVER).build();
		ImageDto.Spec medium = ImageDto.Spec.builder().name("medium").width(300).height(300).build();
		ImageDto.Spec large = ImageDto.Spec.builder().name("large").width(2000).height(2000).build();

		assertThat(ImageUtils.subsampling(4000, 3000, List.of(thumbnail))).isEqualTo(23);            // COVER 64/3000 → 1/46.9
		assertThat(ImageUtils.subsampling(4000, 3000, List.of(thumbnail, medium))).isEqualTo(6);     // FIT 300/4000 → 1/13.3
		assertThat(ImageUtils.subsampling(4000, 3000, List.of(thumbnail, medium, large))).isEqualTo(1);    // FIT 2000/4000 = 1/2
	}

	@Test
	@DisplayName("큰 JPEG 서브샘플링 — 3000×2001 을 1/5 로 읽어도 결과 크기는 원본 기준(FIT 300×200, COVER 150×150), 색 위치 유지")
	void largeJpegSubsampled() throws Exception {
		byte[] jpeg = encode(halves(3000, 2001), "jpg");
		List<ImageDto.Spec> specs = List.of(
			ImageDto.Spec.builder().name("fit").width(300).height(300).format(ImageDto.Format.PNG).build(),
			ImageDto.Spec.builder().name("cover").width(150).height(150).mode(ImageDto.Mode.COVER).format(ImageDto.Format.PNG).build());
		assertThat(ImageUtils.subsampling(3000, 2001, specs)).isEqualTo(5);    // 디코딩 600×401 — 그 크기로 계산하면 300×201 이 된다

		List<ImageDto.Result> results = ImageUtils.variants(jpeg, specs);

		BufferedImage fit = decode(results.get(0).getBytes());
		assertThat(results.get(0).getWidth()).isEqualTo(300);
		assertThat(results.get(0).getHeight()).isEqualTo(200);
		assertThat(fit.getWidth()).isEqualTo(300);
		assertThat(fit.getHeight()).isEqualTo(200);
		assertThat(isRed(fit.getRGB(20, 100))).isTrue();
		assertThat(isBlue(fit.getRGB(280, 100))).isTrue();

		BufferedImage cover = decode(results.get(1).getBytes());
		assertThat(cover.getWidth()).isEqualTo(150);
		assertThat(cover.getHeight()).isEqualTo(150);
		assertThat(isRed(cover.getRGB(10, 75))).isTrue();
		assertThat(isBlue(cover.getRGB(140, 75))).isTrue();
	}

	@Test
	@DisplayName("큰 JPEG + EXIF 6 — 건너뛰어 읽은 뒤 바로 세움 (3000×2001 → 세로 200×300, 위 빨강·아래 파랑)")
	void largeJpegSubsampledWithExif() throws Exception {
		byte[] phonePhoto = withOrientation(encode(halves(3000, 2001), "jpg"), 6);

		ImageDto.Result upright = ImageUtils.resize(phonePhoto, 300, 300, ImageDto.Format.JPEG);
		BufferedImage image = decode(upright.getBytes());

		assertThat(upright.getWidth()).isEqualTo(200);
		assertThat(upright.getHeight()).isEqualTo(300);
		assertThat(image.getWidth()).isEqualTo(200);
		assertThat(image.getHeight()).isEqualTo(300);
		assertThat(isRed(image.getRGB(100, 30))).isTrue();
		assertThat(isBlue(image.getRGB(100, 270))).isTrue();
	}
}
