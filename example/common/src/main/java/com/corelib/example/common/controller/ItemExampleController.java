// common 예제 API (ApiResponse 응답, 예외는 전역 핸들러가 변환)
package com.corelib.example.common.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.common.dto.ItemExampleDto;
import com.corelib.example.common.service.ItemExampleService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 컨트롤러는 try-catch 없이 서비스만 부른다 — 실패 응답은 common 의 CoreExceptionHandler 가 만든다 */
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemExampleController {

	private final ItemExampleService itemExampleService;

	@PostMapping("/create")
	public ApiResponse<ItemExampleDto.ItemResponse> create(@RequestBody ItemExampleDto.CreateRequest request) {
		return ApiResponse.success(itemExampleService.create(request));
	}

	@GetMapping("/detail")
	public ApiResponse<ItemExampleDto.ItemResponse> detail(@RequestParam Long id) {
		return ApiResponse.success(itemExampleService.detail(id));
	}

	@GetMapping("/list")
	public ApiResponse<List<ItemExampleDto.ItemResponse>> list() {
		return ApiResponse.success(itemExampleService.list());
	}

	@PostMapping("/purchase")
	public ApiResponse<ItemExampleDto.ItemResponse> purchase(@RequestBody ItemExampleDto.PurchaseRequest request) {
		return ApiResponse.success(itemExampleService.purchase(request));
	}

	@GetMapping("/unexpected-error")
	public ApiResponse<Void> unexpected() {
		itemExampleService.unexpected();
		return ApiResponse.success();
	}
}
