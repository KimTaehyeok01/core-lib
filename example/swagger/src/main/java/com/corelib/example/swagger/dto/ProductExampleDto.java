package com.corelib.example.swagger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 상품 예제 데이터 전송 객체 */
public class ProductExampleDto {

	// === 등록 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	@Schema(description = "상품 등록 요청")
	public static class CreateRequest {

		@Schema(description = "상품명", example = "무선 키보드", maxLength = 50)
		@NotBlank
		@Size(max = 50)
		private String name;

		@Schema(description = "판매가(원)", example = "39000", minimum = "1")
		@NotNull
		@Positive
		private Integer price;
	}

	// === 상품 응답 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@Schema(description = "상품")
	public static class ProductResponse {

		@Schema(description = "상품 ID", example = "1")
		private final Long id;

		@Schema(description = "상품명", example = "무선 키보드")
		private final String name;

		@Schema(description = "판매가(원)", example = "39000")
		private final Integer price;
	}
}
