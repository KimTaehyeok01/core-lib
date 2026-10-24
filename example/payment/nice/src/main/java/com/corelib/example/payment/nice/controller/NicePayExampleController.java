package com.corelib.example.payment.nice.controller;

import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.payment.nice.dto.NicePayExampleDto;
import com.corelib.example.payment.nice.service.NicePayExampleService;
import com.corelib.payment.nice.NicePayDto;
import com.corelib.payment.nice.NicePayError;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
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

	/**
	 * returnUrl — application/x-www-form-urlencoded 로 인증결과가 온다.
	 * BindingResult 를 받아 형식 오류(amount=abc 등)도 JSON 400 대신 실패 화면으로 보낸다 — 누락값은 서비스가 검증한다.
	 */
	@PostMapping("/nicepay/return")
	public ResponseEntity<Void> returnUrl(@ModelAttribute NicePayDto.AuthResult authResult, BindingResult bindingResult) {
		if (bindingResult.hasErrors()) {
			return failRedirect(NicePayError.INVALID_AUTH_RESULT.code(), NicePayError.INVALID_AUTH_RESULT.message());
		}
		try {
			nicePayExampleService.approve(authResult);
			return redirect(UriComponentsBuilder.fromPath("/result.html").queryParam("orderId", authResult.getOrderId()));
		} catch (CoreException e) {
			return failRedirect(e.getErrorCode().code(), e.getMessage());
		}
	}

	/** 예제는 인증 없음 — 실서비스는 주문 소유자/관리자 확인 필수 (orderId 만으로 조회되면 안 된다) */
	@GetMapping("/api/nicepay/orders/detail")
	public ApiResponse<NicePayExampleDto.OrderDetail> detail(@RequestParam String orderId) {
		return ApiResponse.success(nicePayExampleService.detail(orderId));
	}

	/** 예제는 인증 없음 — 실서비스는 주문 소유자/관리자 확인 필수 (orderId 만으로 환불되면 안 된다) */
	@PostMapping("/api/nicepay/orders/cancel")
	public ApiResponse<NicePayExampleDto.OrderDetail> cancel(@RequestBody NicePayExampleDto.CancelRequest request) {
		return ApiResponse.success(nicePayExampleService.cancel(request));
	}

	private static ResponseEntity<Void> failRedirect(String code, String message) {
		return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("code", code).queryParam("message", message));
	}

	private static ResponseEntity<Void> redirect(UriComponentsBuilder location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location.encode().build().toUriString())).build();
	}
}
