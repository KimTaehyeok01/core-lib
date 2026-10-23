package com.corelib.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
