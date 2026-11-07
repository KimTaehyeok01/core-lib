package com.corelib.example.redis.dto;

import jakarta.validation.constraints.NotNull;

/** 선착순 쿠폰 전송 객체 */
public class CouponDto {

	private CouponDto() {
	}

	/** 쿠폰 발급 요청 — couponId 와 memberId 를 함께 보낸다 (KISA: PathVariable 금지) */
	public record IssueRequest(@NotNull Long couponId, @NotNull Long memberId) {
	}

	/** 쿠폰 발급 결과 — 발급된 쿠폰 id, 받은 멤버 id, 남은 재고 */
	public record IssueResponse(Long couponId, Long memberId, long remainingStock) {
	}
}
