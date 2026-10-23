package com.corelib.example.webclient.controller;

import com.corelib.example.webclient.dto.ShipmentExampleDto;
import com.corelib.example.webclient.service.FakeCourierService;
import com.corelib.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 택배사 역할. 응답 형식은 일반적인 택배사 REST API 모양 — 성공은 본문 그대로, 실패는 4xx + {code, message}.
 * example.courier.api-key 가 있으면 Bearer 인증을 확인한다.
 */
@RestController
@RequestMapping("/fake-courier/v1/shipments")
public class FakeCourierController {

	private final FakeCourierService fakeCourierService;
	private final String apiKey;

	public FakeCourierController(FakeCourierService fakeCourierService, @Value("${example.courier.api-key:}") String apiKey) {
		this.fakeCourierService = fakeCourierService;
		this.apiKey = apiKey;
	}

	@PostMapping
	public ResponseEntity<Object> register(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
										   @RequestBody ShipmentExampleDto.CourierRegisterRequest request) {
		if (!isAuthorized(authorization)) {
			return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "API 키가 올바르지 않습니다.");
		}
		if (StringUtils.isBlank(request.getOrderNo()) || StringUtils.isBlank(request.getAddress())) {
			return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "주문번호와 주소는 필수입니다.");
		}
		return fakeCourierService.register(request)
								 .<ResponseEntity<Object>>map(ResponseEntity::ok)
								 .orElseGet(() -> error(HttpStatus.CONFLICT, "DUPLICATE_ORDER", "이미 접수된 주문번호입니다."));
	}

	@GetMapping("/by-order")
	public ResponseEntity<Object> findByOrderNo(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
												@RequestParam String orderNo) {
		if (!isAuthorized(authorization)) {
			return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "API 키가 올바르지 않습니다.");
		}
		return fakeCourierService.findByOrderNo(orderNo)
								 .<ResponseEntity<Object>>map(ResponseEntity::ok)
								 .orElseGet(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "접수 내역이 없습니다."));
	}

	@GetMapping("/track")
	public ResponseEntity<Object> track(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
										@RequestParam String invoiceNo) {
		if (!isAuthorized(authorization)) {
			return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "API 키가 올바르지 않습니다.");
		}
		return fakeCourierService.track(invoiceNo)
								 .<ResponseEntity<Object>>map(ResponseEntity::ok)
								 .orElseGet(() -> error(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND", "송장번호가 없습니다."));
	}

	private boolean isAuthorized(String authorization) {
		return StringUtils.isBlank(apiKey) || ("Bearer " + apiKey.trim()).equals(authorization);
	}

	private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ShipmentExampleDto.CourierError(code, message));
	}
}
