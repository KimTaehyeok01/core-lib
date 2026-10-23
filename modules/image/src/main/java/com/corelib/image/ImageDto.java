package com.corelib.image;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 이미지 요청·응답 데이터 */
public class ImageDto {

	/** 출력 형식 — webp 는 읽기만 된다 */
	public enum Format {
		/** 사진 — 투명 영역은 흰 배경으로 */
		JPEG("jpg", "image/jpeg"),
		/** 투명·도형·스크린샷 */
		PNG("png", "image/png");

		private final String extension;
		private final String contentType;

		Format(String extension, String contentType) {
			this.extension = extension;
			this.contentType = contentType;
		}

		public String getExtension() {
			return extension;
		}

		public String getContentType() {
			return contentType;
		}
	}

	/** 크기 맞추는 방식 */
	public enum Mode {
		/** 상자 안에 들어가게 비율 유지 축소 — 잘리는 곳 없음, 결과가 상자보다 작을 수 있다 */
		FIT,
		/** 상자를 꽉 채우고 넘치는 쪽을 가운데 기준으로 잘라냄 — 정사각 썸네일 */
		COVER
	}

	// === 만들 규격 하나 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Spec {

		/** 결과 이름 (예: thumbnail, medium) */
		private final String name;

		private final int width;

		private final int height;

		@Builder.Default
		private final Mode mode = Mode.FIT;

		@Builder.Default
		private final Format format = Format.JPEG;

		/** JPEG 품질 0.0~1.0 */
		@Builder.Default
		private final float quality = 0.85f;
	}

	// === 이미지 정보 (디코딩 없이 헤더만) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Info {

		private final int width;

		private final int height;

		/** jpeg · png · gif · bmp · webp */
		private final String format;
	}

	// === 변환 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Result {

		private final String name;

		private final byte[] bytes;

		private final int width;

		private final int height;

		private final Format format;
	}
}
