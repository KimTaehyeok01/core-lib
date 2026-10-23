package com.corelib.example.swagger.controller;

import com.corelib.common.response.ApiResponse;
import com.corelib.example.swagger.dto.ProductExampleDto;
import com.corelib.example.swagger.service.ProductExampleService;
import com.corelib.swagger.SwaggerAutoConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 예제 API.
 * swagger 의 ApiResponse 어노테이션은 common 의 ApiResponse 와 이름이 같아 패키지까지 적는다.
 */
@Tag(name = "상품", description = "swagger 모듈 문서화 예제")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductExampleController {

	private final ProductExampleService productExampleService;

	@Operation(summary = "상품 목록", description = "인증 없이 조회할 수 있는 공개 API")
	@GetMapping("/list")
	public ApiResponse<List<ProductExampleDto.ProductResponse>> list() {
		return ApiResponse.success(productExampleService.list());
	}

	/** 자물쇠 표시 = bearerAuth 필요 — 실제 인증 필터는 소비 앱(Spring Security 등)이 담당 */
	@Operation(summary = "상품 등록", description = "Swagger UI 의 Authorize 에 JWT 를 넣고 호출하는 API")
	@SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 성공"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 검증 실패 (C400)")
	})
	@PostMapping("/create")
	public ApiResponse<ProductExampleDto.ProductResponse> create(@RequestBody ProductExampleDto.CreateRequest request) {
		return ApiResponse.success(productExampleService.create(request));
	}
}
