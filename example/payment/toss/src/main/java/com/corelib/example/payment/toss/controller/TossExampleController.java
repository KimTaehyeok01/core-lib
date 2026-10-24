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

	/** 실패 화면 문구 — 서명 없는 브라우저 쿼리 message 는 보여주지 않는다 */
	private static final String NOT_COMPLETED_MESSAGE = "결제가 완료되지 않았습니다. 다시 시도해 주세요.";

	private final TossExampleService tossExampleService;

	@PostMapping("/api/toss/orders/create")
	public ApiResponse<TossExampleDto.OrderResponse> createOrder(@RequestBody TossExampleDto.OrderRequest request) {
		return ApiResponse.success(tossExampleService.createOrder(request));
	}

	/** successUrl — 10분 안에 승인해야 한다. 파라미터 누락·형식 오류도 JSON 400 이 아니라 실패 화면으로 보낸다 */
	@GetMapping("/toss/success")
	public ResponseEntity<Void> success(@RequestParam(required = false) String paymentKey, @RequestParam(required = false) String orderId,
										@RequestParam(required = false) String amount) {
		try {
			TossExampleDto.OrderDetail order = tossExampleService.confirm(paymentKey, orderId, amount);
			// 재진입·동시 콜백 — 결제가 진행·성립된 주문은 결과 화면에서 상태를 조회한다.
			// READY 는 동시 콜백의 승자가 거절돼 되돌린 경우뿐이다
			if (order.getStatus() == TossExampleDto.OrderStatus.FAILED || order.getStatus() == TossExampleDto.OrderStatus.READY) {
				return redirectFail(orderId, NOT_COMPLETED_MESSAGE);
			}
			return redirect(UriComponentsBuilder.fromPath("/result.html").queryParam("orderId", orderId));
		} catch (CoreException e) {
			return redirectFail(orderId, e.getMessage());
		}
	}

	/**
	 * failUrl — 사용자가 결제창을 닫으면 code=PAY_PROCESS_CANCELED, orderId 없음.
	 * 서명 없는 브라우저 입력이라 주문 상태를 바꾸지 않고(READY 유지, 재시도 가능) 쿼리 message 대신 서버 고정 문구를 보여준다.
	 */
	@GetMapping("/toss/fail")
	public ResponseEntity<Void> fail(@RequestParam(required = false) String code) {
		return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("code", code).queryParam("message", NOT_COMPLETED_MESSAGE));
	}

	/** 예제는 인증 없음 — 실서비스는 주문 소유자/관리자 확인 필수 */
	@GetMapping("/api/toss/orders/detail")
	public ApiResponse<TossExampleDto.OrderDetail> detail(@RequestParam String orderId) {
		return ApiResponse.success(tossExampleService.detail(orderId));
	}

	/** 예제는 인증 없음 — 실서비스는 주문 소유자/관리자 확인 필수 */
	@PostMapping("/api/toss/orders/cancel")
	public ApiResponse<TossExampleDto.OrderDetail> cancel(@RequestBody TossExampleDto.CancelRequest request) {
		return ApiResponse.success(tossExampleService.cancel(request));
	}

	private static ResponseEntity<Void> redirectFail(String orderId, String message) {
		return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("orderId", orderId).queryParam("message", message));
	}

	private static ResponseEntity<Void> redirect(UriComponentsBuilder location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location.encode().build().toUriString())).build();
	}
}
