package com.corelib.example.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 상품 예제 데이터 전송 객체 */
public class ItemExampleDto {

	// === 상품 등록 요청 — 어노테이션은 서비스의 ValidateUtils.validate 가 검사 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class CreateRequest {

		@NotBlank(message = "상품명을 입력하세요.")
		@Size(max = 50)
		private String name;

		@NotNull
		@Positive(message = "가격은 0보다 커야 합니다.")
		@Max(10_000_000)
		private Integer price;

		@NotNull
		@PositiveOrZero
		private Integer stock;
	}

	// === 구매 요청 ===
	@Getter
	@Setter
	@NoArgsConstructor
	public static class PurchaseRequest {

		@NotNull
		private Long itemId;
	}

	// === 상품 응답 ===
	@Getter
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class ItemResponse {

		private final Long id;

		private final String name;

		private final Integer price;

		private final Integer stock;
	}
}
