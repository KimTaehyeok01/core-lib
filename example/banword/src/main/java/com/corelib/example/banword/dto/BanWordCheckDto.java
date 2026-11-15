package com.corelib.example.banword.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 금지어 검사 예제 DTO */
public class BanWordCheckDto {

	private BanWordCheckDto() {
	}

	// === 요청 ===

	/** 검사 대상 텍스트 */
	public record Request(
		@NotBlank
		@Size(max = 10_000)
		String text
	) {
	}

	/** 운영 /admin/* 엔드포인트 요청 — 금지어 또는 화이트리스트 단어 추가. */
	public record AdminAddRequest(
		@NotBlank
		@Size(max = 100)
		String word
	) {
	}

	// === 응답 ===

	/** 매칭된 금지어 원형 목록 */
	public record Response(List<String> banWords) {
	}
}
