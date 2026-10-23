package com.corelib.file;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 파일 저장소 데이터 전송 객체 */
public class FileDto {

	// === 업로드 결과 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class StoredFile {

		/** 저장소 내 키 — DB 에는 이 값을 저장하고 다운로드/삭제에 쓴다 */
		private final String key;

		/** 사용자가 올린 원본 파일명 — 다운로드 시 Content-Disposition 파일명으로 쓴다 */
		private final String originalName;

		/** 바이트 크기 */
		private final long size;

		/** MIME 타입 (클라이언트 신고값이라 신뢰하지 않는다) */
		private final String contentType;

		/** 접근 URL — S3 는 만료되는 presigned URL 이라 저장하지 말고 필요할 때 getUrl(key) 로 재발급한다 */
		private final String url;
	}
}
