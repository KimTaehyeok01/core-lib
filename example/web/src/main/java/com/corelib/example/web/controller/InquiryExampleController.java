package com.corelib.example.web.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.web.dto.InquiryExampleDto;
import com.corelib.example.web.service.InquiryExampleService;
import com.corelib.web.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의 API.
 * <ul>
 *   <li>/api/inquiries/** — 공개 (yml core.web.security.permit-paths)</li>
 *   <li>/api/admin/** — ADMIN 만 (X-Admin-Key). 키가 없으면 core 보안 체인이 401 JSON 으로 막는다</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class InquiryExampleController {

	private final InquiryExampleService inquiryExampleService;

	@PostMapping("/api/inquiries/create")
	public ApiResponse<InquiryExampleDto.CreateResponse> create(@RequestBody InquiryExampleDto.CreateRequest request,
																HttpServletRequest httpRequest) {
		return ApiResponse.success(inquiryExampleService.create(request, ClientIpUtils.resolve(httpRequest)));
	}

	@GetMapping("/api/admin/inquiries/list")
	public ApiResponse<List<InquiryExampleDto.InquirySummary>> list() {
		return ApiResponse.success(inquiryExampleService.list());
	}

	@PostMapping("/api/admin/inquiries/answer")
	public ApiResponse<Void> answer(@RequestBody InquiryExampleDto.AnswerRequest request) {
		inquiryExampleService.answer(request);
		return ApiResponse.success();
	}
}
