package com.corelib.example.payment.toss.controller;

import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.payment.toss.dto.TossExampleDto;
import com.corelib.example.payment.toss.service.TossExampleService;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 흐름: 주문 생성 → 화면이 SDK requestPayment → 토스가 successUrl(?paymentKey&orderId&amount) 로 리다이렉트
 * → 서버 승인 → /result.html. 실패·사용자 취소는 failUrl → /fail.html.
 */
@RestController
@RequiredArgsConstructor
public class TossExampleController {

	private final TossExampleService tossExampleService;

	@PostMapping("/api/toss/orders/create")
	public ApiResponse<TossExampleDto.OrderResponse> createOrder(@RequestBody TossExampleDto.OrderRequest request) {
		return ApiResponse.success(tossExampleService.createOrder(request));
	}

	/** successUrl — 10분 안에 승인해야 한다 */
	@GetMapping("/toss/success")
	public ResponseEntity<Void> success(@RequestParam String paymentKey, @RequestParam String orderId, @RequestParam Integer amount) {
		try {
			tossExampleService.confirm(paymentKey, orderId, amount);
			return redirect(UriComponentsBuilder.fromPath("/result.html").queryParam("orderId", orderId));
		} catch (CoreException e) {
			return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("orderId", orderId).queryParam("message", e.getMessage()));
		}
	}

	/** failUrl — 사용자가 결제창을 닫으면 code=PAY_PROCESS_CANCELED, orderId 없음 */
	@GetMapping("/toss/fail")
	public ResponseEntity<Void> fail(@RequestParam(required = false) String code, @RequestParam(required = false) String message,
									 @RequestParam(required = false) String orderId) {
		tossExampleService.markFailed(orderId, message);
		return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("code", code).queryParam("message", message));
	}

	@GetMapping("/api/toss/orders/detail")
	public ApiResponse<TossExampleDto.OrderDetail> detail(@RequestParam String orderId) {
		return ApiResponse.success(tossExampleService.detail(orderId));
	}

	@PostMapping("/api/toss/orders/cancel")
	public ApiResponse<TossExampleDto.OrderDetail> cancel(@RequestBody TossExampleDto.CancelRequest request) {
		return ApiResponse.success(tossExampleService.cancel(request));
	}

	private static ResponseEntity<Void> redirect(UriComponentsBuilder location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location.encode().build().toUriString())).build();
	}
}
