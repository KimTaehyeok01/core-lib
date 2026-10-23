package com.corelib.example.crypto.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.crypto.dto.CustomerExampleDto;
import com.corelib.example.crypto.service.CustomerExampleService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 고객 API — 가입·로그인·휴대폰 변경·목록, 저장 형태 확인 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerExampleController {

	private final CustomerExampleService customerExampleService;

	@PostMapping("/join")
	public ApiResponse<CustomerExampleDto.CustomerResponse> join(@RequestBody CustomerExampleDto.JoinRequest request) {
		return ApiResponse.success(customerExampleService.join(request));
	}

	@PostMapping("/login")
	public ApiResponse<CustomerExampleDto.CustomerResponse> login(@RequestBody CustomerExampleDto.LoginRequest request) {
		return ApiResponse.success(customerExampleService.login(request));
	}

	@PostMapping("/change-mobile")
	public ApiResponse<CustomerExampleDto.CustomerResponse> changeMobile(@RequestBody CustomerExampleDto.ChangeMobileRequest request) {
		return ApiResponse.success(customerExampleService.changeMobile(request));
	}

	@GetMapping("/list")
	public ApiResponse<List<CustomerExampleDto.CustomerResponse>> list() {
		return ApiResponse.success(customerExampleService.list());
	}

	/** 예제 확인용 — 실서비스에는 두지 않는다 */
	@GetMapping("/stored-rows")
	public ApiResponse<List<CustomerExampleDto.StoredRow>> storedRows() {
		return ApiResponse.success(customerExampleService.storedRows());
	}
}
