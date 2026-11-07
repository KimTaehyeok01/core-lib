package com.corelib.example.redis.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.redis.dto.CouponDto;
import com.corelib.example.redis.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 API — 선착순 발급 데모.
 * <ul>
 *   <li>{@code POST /coupons/issue} — body: {@link CouponDto.IssueRequest}. 같은 (쿠폰, 멤버) 로 동시에 요청해도 하나만 통과</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/coupons")
public class CouponController {

	private final CouponService couponService;

	@PostMapping("/issue")
	public ApiResponse<CouponDto.IssueResponse> issue(@RequestBody @Valid CouponDto.IssueRequest request) {
		return ApiResponse.success(couponService.issue(request.couponId(), request.memberId()));
	}
}
