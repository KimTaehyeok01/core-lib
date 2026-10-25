package com.corelib.image;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * JPEG EXIF 방향값(Orientation, 태그 0x0112) 읽기와 보정.
 * 휴대폰은 사진을 돌리지 않고 "몇 도 돌려 보라"는 값만 적어 두는데, ImageIO 는 이 값을 무시해 옆으로 누운 썸네일이 된다.
 * 외부 라이브러리 없이 APP1 세그먼트의 IFD0 만 읽는다 — 모든 오프셋은 범위를 확인한다.
 */
final class ExifOrientation {

	static final int NORMAL = 1;
	private static final int ORIENTATION_TAG = 0x0112;

	private ExifOrientation() {
	}

	/** @return 1~8, 없거나 읽을 수 없으면 1 */
	static int read(byte[] jpeg) {
		if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			return NORMAL;
		}
		int offset = 2;
		while (offset + 4 <= jpeg.length && (jpeg[offset] & 0xFF) == 0xFF) {
			int marker = jpeg[offset + 1] & 0xFF;
			int length = u16(jpeg, offset + 2, false);
			if (marker == 0xDA || length < 2 || offset + 2 + length > jpeg.length) {
				return NORMAL;    // 영상 데이터 시작(SOS) 또는 깨진 길이 — 더 읽지 않는다
			}
			if (marker == 0xE1 && length >= 8 && "Exif".equals(new String(jpeg, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
				return readTiff(jpeg, offset + 10, offset + 2 + length);
			}
			offset += 2 + length;
		}
		return NORMAL;
	}

	private static int readTiff(byte[] data, int tiff, int end) {
		if (tiff + 8 > end) {
			return NORMAL;
		}
		boolean isLittle = data[tiff] == 'I' && data[tiff + 1] == 'I';
		if (!isLittle && !(data[tiff] == 'M' && data[tiff + 1] == 'M')) {
			return NORMAL;
		}
		long ifd = tiff + u32(data, tiff + 4, isLittle);
		if (ifd < tiff || ifd + 2 > end) {
			return NORMAL;
		}
		int count = u16(data, (int) ifd, isLittle);
		for (int i = 0; i < count; i++) {
			int entry = (int) ifd + 2 + i * 12;
			if (entry + 12 > end) {
				return NORMAL;
			}
			if (u16(data, entry, isLittle) == ORIENTATION_TAG) {
				int value = u16(data, entry + 8, isLittle);
				return value >= 1 && value <= 8 ? value : NORMAL;
			}
		}
		return NORMAL;
	}

	/** 방향값대로 돌려 바로 세운 이미지 (1 이면 그대로) */
	static BufferedImage apply(BufferedImage image, int orientation) {
		if (orientation == NORMAL) {
			return image;
		}
		int width = image.getWidth();
		int height = image.getHeight();
		boolean isSwapped = orientation >= 5;
		AffineTransform transform = new AffineTransform();
		switch (orientation) {
			case 2 -> { transform.translate(width, 0); transform.scale(-1, 1); }               // 좌우 반전
			case 3 -> { transform.translate(width, height); transform.rotate(Math.PI); }       // 180°
			case 4 -> { transform.translate(0, height); transform.scale(1, -1); }              // 상하 반전
			case 5 -> { transform.rotate(Math.PI / 2); transform.scale(1, -1); }               // 좌우 반전 + 270° (전치)
			case 6 -> { transform.translate(height, 0); transform.rotate(Math.PI / 2); }       // 시계 90°
			case 7 -> { transform.scale(-1, 1); transform.translate(-height, 0);
				transform.translate(0, width); transform.rotate(-Math.PI / 2); }              // 좌우 반전 + 90° (반전치)
			case 8 -> { transform.translate(0, width); transform.rotate(-Math.PI / 2); }       // 반시계 90°
			default -> {
				return image;
			}
		}
		BufferedImage result = new BufferedImage(isSwapped ? height : width, isSwapped ? width : height,
			image.getType() != BufferedImage.TYPE_CUSTOM ? image.getType()
				: image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = result.createGraphics();
		graphics.drawImage(image, transform, null);
		graphics.dispose();
		return result;
	}

	private static int u16(byte[] data, int at, boolean isLittle) {
		return isLittle ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
	}

	private static long u32(byte[] data, int at, boolean isLittle) {
		return isLittle
			? (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16 | (data[at + 3] & 0xFFL) << 24
			: (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | (data[at + 3] & 0xFFL);
	}
}
