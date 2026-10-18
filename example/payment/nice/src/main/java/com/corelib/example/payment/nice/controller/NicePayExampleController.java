// 나이스페이 결제 예제 API + returnUrl 콜백
package com.corelib.example.payment.nice.controller;

import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.payment.nice.dto.NicePayExampleDto;
import com.corelib.example.payment.nice.service.NicePayExampleService;
import com.corelib.payment.nice.NicePayDto;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 흐름: 주문 생성 → 화면이 AUTHNICE.requestPay → NICEPAY 가 returnUrl 로 인증결과 form POST
 * → 서버 검증·승인 → /result.html. 실패는 /fail.html.
 */
@RestController
@RequiredArgsConstructor
public class NicePayExampleController {

	private final NicePayExampleService nicePayExampleService;

	@PostMapping("/api/nicepay/orders/create")
	public ApiResponse<NicePayExampleDto.OrderResponse> createOrder(@RequestBody NicePayExampleDto.OrderRequest request) {
		return ApiResponse.success(nicePayExampleService.createOrder(request));
	}

	/** returnUrl — application/x-www-form-urlencoded 로 인증결과가 온다 */
	@PostMapping("/nicepay/return")
	public ResponseEntity<Void> returnUrl(@ModelAttribute NicePayDto.AuthResult authResult) {
		try {
			nicePayExampleService.approve(authResult);
			return redirect(UriComponentsBuilder.fromPath("/result.html").queryParam("orderId", authResult.getOrderId()));
		} catch (CoreException e) {
			return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("code", e.getErrorCode().code())
												.queryParam("message", e.getMessage()));
		}
	}

	@GetMapping("/api/nicepay/orders/detail")
	public ApiResponse<NicePayExampleDto.OrderDetail> detail(@RequestParam String orderId) {
		return ApiResponse.success(nicePayExampleService.detail(orderId));
	}

	@PostMapping("/api/nicepay/orders/cancel")
	public ApiResponse<NicePayExampleDto.OrderDetail> cancel(@RequestBody NicePayExampleDto.CancelRequest request) {
		return ApiResponse.success(nicePayExampleService.cancel(request));
	}

	private static ResponseEntity<Void> redirect(UriComponentsBuilder location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location.encode().build().toUriString())).build();
	}
}
