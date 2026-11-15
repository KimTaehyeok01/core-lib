package com.corelib.example.banword.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.banword.dto.BanWordCheckDto;
import com.corelib.example.banword.service.BanWordCheckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 금지어 검사 API.
 *
 * <p><b>/admin/* 엔드포인트는 운영 노출 금지</b> — 예제에는 인증이 없다.
 * 실서비스에서는 반드시 ROLE_ADMIN 또는 동등 인가로 보호할 것.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/banwords")
public class BanWordCheckController {

	private final BanWordCheckService service;

	@PostMapping("/check")
	public ApiResponse<BanWordCheckDto.Response> check(@Valid @RequestBody BanWordCheckDto.Request request) {
		return ApiResponse.success(service.check(request));
	}

	@PostMapping("/check-with-tokens")
	public ApiResponse<BanWordCheckDto.Response> checkWithTokens(@Valid @RequestBody BanWordCheckDto.Request request) {
		return ApiResponse.success(service.checkWithTokens(request));
	}

	@PostMapping("/evict")
	public ApiResponse<Void> evict() {
		service.evict();
		return ApiResponse.success();
	}

	/** ROLE_ADMIN 전용 (예제는 인증 없음) — InMemorySource 에 금지어 추가 + evict. */
	@PostMapping("/admin/ban")
	public ApiResponse<Void> addBan(@Valid @RequestBody BanWordCheckDto.AdminAddRequest request) {
		service.addBan(request);
		return ApiResponse.success();
	}

	/** ROLE_ADMIN 전용 (예제는 인증 없음) — InMemorySource 에 화이트 단어 추가 + evict. */
	@PostMapping("/admin/white")
	public ApiResponse<Void> addWhite(@Valid @RequestBody BanWordCheckDto.AdminAddRequest request) {
		service.addWhite(request);
		return ApiResponse.success();
	}
}
