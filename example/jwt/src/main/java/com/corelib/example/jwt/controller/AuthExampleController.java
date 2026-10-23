package com.corelib.example.jwt.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.jwt.dto.AuthExampleDto;
import com.corelib.example.jwt.service.AuthExampleService;
import com.corelib.jwt.JwtDto;
import com.corelib.jwt.JwtUtils;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 API.
 * <ul>
 *   <li>/api/auth/** · /.well-known/jwks.json — 공개 (yml permit-paths)</li>
 *   <li>/api/members/** — Bearer 액세스 토큰 필요 (jwt 모듈 필터가 검증)</li>
 *   <li>/api/admin/** — ADMIN 권한 (경로 규칙 — JwtExampleApplication.adminOnly)</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class AuthExampleController {

	private final AuthExampleService authExampleService;

	@PostMapping("/api/auth/signup")
	public ApiResponse<AuthExampleDto.MemberResponse> signup(@RequestBody AuthExampleDto.SignupRequest request) {
		return ApiResponse.success(authExampleService.signup(request));
	}

	@PostMapping("/api/auth/login")
	public ApiResponse<JwtDto.TokenPair> login(@RequestBody AuthExampleDto.LoginRequest request) {
		return ApiResponse.success(authExampleService.login(request));
	}

	@PostMapping("/api/auth/refresh")
	public ApiResponse<JwtDto.TokenPair> refresh(@RequestBody AuthExampleDto.RefreshRequest request) {
		return ApiResponse.success(authExampleService.refresh(request));
	}

	/** claims 는 Bearer 필터가 검증해 넣어 준 값 */
	@GetMapping("/api/members/me")
	public ApiResponse<AuthExampleDto.MemberResponse> me(@AuthenticationPrincipal JwtDto.Claims claims) {
		return ApiResponse.success(authExampleService.me(claims));
	}

	@PostMapping("/api/members/logout")
	public ApiResponse<Void> logout(@AuthenticationPrincipal JwtDto.Claims claims) {
		authExampleService.logout(claims.getSubject());
		return ApiResponse.success();
	}

	@GetMapping("/api/admin/members/list")
	public ApiResponse<List<AuthExampleDto.MemberResponse>> members() {
		return ApiResponse.success(authExampleService.members());
	}

	/** 다른 서비스가 이 서비스 토큰을 검증할 공개키 — ApiResponse 로 감싸지 않는다 (JWKS 표준 형식) */
	@GetMapping("/.well-known/jwks.json")
	public Map<String, Object> jwks() {
		return JwtUtils.jwks();
	}
}
