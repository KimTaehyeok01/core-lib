package com.corelib.example.redis.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 상품 상세 전송 객체 — 캐시 적중 여부 확인용으로 loadedAt 을 함께 내보낸다 */
public class ProductDto {

	private ProductDto() {
	}

	/** 상품 상세 응답 */
	public record Response(Long id, String name, BigDecimal price, LocalDateTime loadedAt) {
	}
}
