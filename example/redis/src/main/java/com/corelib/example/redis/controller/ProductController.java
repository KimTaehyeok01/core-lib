package com.corelib.example.redis.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.redis.dto.ProductDto;
import com.corelib.example.redis.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 API — 상품 상세 캐시 데모.
 * <ul>
 *   <li>{@code GET /products/find?id=1} — 같은 id 로 두 번 호출해 두 번째 응답의 {@code loadedAt} 이 그대로인지 확인</li>
 *   <li>{@code GET /products/loads} — 실제 메서드 진입 횟수 (캐시 적중이면 오르지 않는다)</li>
 *   <li>{@code POST /products/evict?id=1} — 해당 id 의 캐시 제거</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/products")
public class ProductController {

	private final ProductService productService;

	@GetMapping("/find")
	public ApiResponse<ProductDto.Response> find(@RequestParam Long id) {
		return ApiResponse.success(productService.find(id));
	}

	@GetMapping("/loads")
	public ApiResponse<Integer> loads() {
		return ApiResponse.success(productService.loadCount());
	}

	@PostMapping("/evict")
	public ApiResponse<Void> evict(@RequestParam Long id) {
		productService.evict(id);
		return ApiResponse.success();
	}
}
