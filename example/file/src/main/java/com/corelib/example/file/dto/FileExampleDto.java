// file 예제 요청·응답 데이터
package com.corelib.example.file.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.core.io.Resource;

/** 파일 예제 데이터 전송 객체 */
public class FileExampleDto {

	// === 첨부 정보 (실서비스는 첨부 테이블 한 행) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class FileInfo {

		/** 저장소 키 — DB 에는 이 값을 저장한다 */
		private final String key;

		/** 원본 파일명 — 다운로드 파일명으로 쓴다 */
		private final String originalName;

		private final Long size;

		private final String contentType;

		private final LocalDateTime uploadedAt;
	}

	// === 삭제 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class DeleteRequest {

		@NotBlank
		private String key;
	}

	// === 다운로드 (컨트롤러가 응답 헤더를 만든다) ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class Download {

		private final FileInfo info;

		private final Resource resource;
	}
}
