package com.corelib.example.extractor.dto;

import com.corelib.extractor.KeywordExtractorDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/** 키워드 추출 예제 DTO */
public class KeywordDto {

	private KeywordDto() {
	}

	// === 요청 ===

	/** 키워드 추출 요청 — pos 가 null 이면 Noun 만 */
	public record ExtractRequest(
		@NotBlank @Size(max = 10_000) String text,
		Set<String> pos
	) {
	}

	/** 단일 텍스트 요청 */
	public record TextRequest(
		@NotBlank @Size(max = 10_000) String text
	) {
	}

	// === 응답 ===

	/** 키워드 추출 응답 */
	public record Response(Set<String> keywords) {
	}

	/** 토큰 응답 */
	public record TokenResponse(List<KeywordExtractorDto.Token> tokens) {
	}

	/** 정규화 응답 */
	public record NormalizeResponse(String normalized) {
	}
}
