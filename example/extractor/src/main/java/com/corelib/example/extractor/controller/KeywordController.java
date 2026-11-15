package com.corelib.example.extractor.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.extractor.dto.KeywordDto;
import com.corelib.example.extractor.service.KeywordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 키워드/토큰/정규화 API */
@RestController
@RequiredArgsConstructor
@RequestMapping("/keywords")
public class KeywordController {

	private final KeywordService service;

	@PostMapping("/extract")
	public ApiResponse<KeywordDto.Response> extract(@Valid @RequestBody KeywordDto.ExtractRequest request) {
		return ApiResponse.success(service.extract(request));
	}

	@PostMapping("/tokens")
	public ApiResponse<KeywordDto.TokenResponse> tokens(@Valid @RequestBody KeywordDto.TextRequest request) {
		return ApiResponse.success(service.tokens(request));
	}

	@PostMapping("/normalize")
	public ApiResponse<KeywordDto.NormalizeResponse> normalize(@Valid @RequestBody KeywordDto.TextRequest request) {
		return ApiResponse.success(service.normalize(request));
	}
}
