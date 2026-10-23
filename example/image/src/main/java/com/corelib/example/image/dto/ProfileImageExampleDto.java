package com.corelib.example.image.dto;

import com.corelib.image.ImageDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 프로필 사진 예제 데이터 전송 객체 */
public class ProfileImageExampleDto {

	// === 저장 모델 — 실서비스는 규격별 파일을 FileUtils(S3)에, 키만 이미지 테이블에 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StoredImage {

		private final String imageId;

		private final ImageDto.Info original;

		/** 규격 이름 → 결과 */
		private final Map<String, ImageDto.Result> variants;

		private final LocalDateTime uploadedAt;
	}

	// === 업로드 응답 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class UploadResponse {

		private final String imageId;

		private final int originalWidth;

		private final int originalHeight;

		private final String originalFormat;

		private final long originalBytes;

		private final List<Variant> variants;
	}

	// === 규격 하나 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Variant {

		private final String name;

		private final int width;

		private final int height;

		private final long bytes;

		/** 보기 주소 */
		private final String url;
	}

	// === 보기 응답 (컨트롤러가 바이트로 내려준다) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ImageFile {

		private final byte[] bytes;

		private final String contentType;
	}
}
