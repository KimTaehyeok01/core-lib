package com.corelib.example.mail.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.mail.dto.EmailVerificationExampleDto;
import com.corelib.example.mail.service.EmailVerificationExampleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 이메일 인증 API — 화면은 static/index.html */
@RestController
@RequestMapping("/api/email-verifications")
@RequiredArgsConstructor
public class EmailVerificationExampleController {

	private final EmailVerificationExampleService emailVerificationExampleService;

	@PostMapping("/send")
	public ApiResponse<EmailVerificationExampleDto.SendResponse> send(@RequestBody EmailVerificationExampleDto.SendRequest request) {
		return ApiResponse.success(emailVerificationExampleService.send(request));
	}

	@PostMapping("/confirm")
	public ApiResponse<EmailVerificationExampleDto.ConfirmResponse> confirm(@RequestBody EmailVerificationExampleDto.ConfirmRequest request) {
		return ApiResponse.success(emailVerificationExampleService.confirm(request));
	}
}
