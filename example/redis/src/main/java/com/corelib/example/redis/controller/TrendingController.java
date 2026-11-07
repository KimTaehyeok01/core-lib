package com.corelib.example.redis.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.redis.dto.TrendingDto;
import com.corelib.example.redis.service.TrendingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인기 검색어 API.
 * <ul>
 *   <li>{@code POST /trending/record} — body: {@link TrendingDto.RecordRequest}. 검색어 점수 +1</li>
 *   <li>{@code GET  /trending/top?n=10} — 상위 N 개 (1~100)</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/trending")
public class TrendingController {

	private final TrendingService trendingService;

	@PostMapping("/record")
	public ApiResponse<Void> record(@RequestBody @Valid TrendingDto.RecordRequest request) {
		trendingService.record(request.keyword());
		return ApiResponse.success();
	}

	@GetMapping("/top")
	public ApiResponse<TrendingDto.Top> top(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int n) {
		return ApiResponse.success(trendingService.top(n));
	}
}
