package com.corelib.example.auth.otp.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.auth.otp.dto.OtpExampleDto;
import com.corelib.example.auth.otp.service.OtpExampleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 2단계 인증 API — 등록·활성화·검증·해제·상태 (화면은 static/index.html) */
@RestController
@RequestMapping("/api/otp")
@RequiredArgsConstructor
public class OtpExampleController {

	private final OtpExampleService otpExampleService;

	@PostMapping("/setup")
	public ApiResponse<OtpExampleDto.SetupResponse> setup(@RequestBody OtpExampleDto.SetupRequest request) {
		return ApiResponse.success(otpExampleService.setup(request));
	}

	@PostMapping("/activate")
	public ApiResponse<OtpExampleDto.StatusResponse> activate(@RequestBody OtpExampleDto.CodeRequest request) {
		return ApiResponse.success(otpExampleService.activate(request));
	}

	@PostMapping("/verify")
	public ApiResponse<OtpExampleDto.StatusResponse> verify(@RequestBody OtpExampleDto.CodeRequest request) {
		return ApiResponse.success(otpExampleService.verify(request));
	}

	@PostMapping("/disable")
	public ApiResponse<OtpExampleDto.StatusResponse> disable(@RequestBody OtpExampleDto.CodeRequest request) {
		return ApiResponse.success(otpExampleService.disable(request));
	}

	@GetMapping("/status")
	public ApiResponse<OtpExampleDto.StatusResponse> status(@RequestParam String memberId) {
		return ApiResponse.success(otpExampleService.status(memberId));
	}
}
