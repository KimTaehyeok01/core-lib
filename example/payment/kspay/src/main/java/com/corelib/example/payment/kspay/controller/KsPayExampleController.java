// KSPAY 결제 예제 API + 결제창 수신(sndReply)·결과 콜백
package com.corelib.example.payment.kspay.controller;

import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.payment.kspay.dto.KsPayExampleDto;
import com.corelib.example.payment.kspay.service.KsPayExampleService;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 흐름 (KSNET 통합모듈 V1.4 공식 샘플과 같은 구조):
 * <ol>
 *   <li>주문 생성 → 화면이 KSPayWeb form 을 채워 {@code _pay(form)} — 결제창(팝업)</li>
 *   <li>결제창 → {@code POST /kspay/rcv}(sndReply) — 결제키를 opener 의 eparamSet/goResult 로 넘기는 작은 페이지</li>
 *   <li>opener 가 KSPayWeb form 을 {@code POST /kspay/result} 로 제출 → 서버 승인 → /result.html</li>
 * </ol>
 */
@RestController
@RequiredArgsConstructor
public class KsPayExampleController {

	private static final String CANCELED_BY_USER = "1";

	private final KsPayExampleService ksPayExampleService;

	@PostMapping("/api/kspay/orders/create")
	public ApiResponse<KsPayExampleDto.OrderResponse> createOrder(@RequestBody KsPayExampleDto.OrderRequest request) {
		return ApiResponse.success(ksPayExampleService.createOrder(request));
	}

	/**
	 * sndReply — 결제창이 결과를 form POST 한다 (공식 샘플 kspay_wh_rcv).
	 * 승인은 여기서 하지 않고 opener 로 결제키만 넘긴다. 값은 HTML 이스케이프해 data 속성으로만 전달한다.
	 */
	@PostMapping(value = "/kspay/rcv", produces = MediaType.TEXT_HTML_VALUE)
	public String receive(@RequestParam(required = false) String reCommConId, @RequestParam(required = false) String reCommType,
						  @RequestParam(required = false) String reHash, @RequestParam(required = false) String reCnclType) {
		boolean isCanceled = CANCELED_BY_USER.equals(reCnclType);
		return """
			<!doctype html>
			<html lang="ko"><head><meta charset="utf-8"><title>KSPAY</title></head>
			<body data-canceled="%s" data-rcid="%s" data-rctype="%s" data-rhash="%s">
			<p>결제 결과를 전달하는 중입니다...</p>
			<script>
				const data = document.body.dataset;
				const target = window.opener || window.parent;
				if (data.canceled === 'true') {
					target.mcancel();
				} else {
					target.eparamSet(data.rcid, data.rctype, data.rhash);
					target.goResult();
				}
				if (window.opener) setTimeout(() => self.close(), 2000);
			</script>
			</body></html>
			""".formatted(isCanceled, escape(reCommConId), escape(reCommType), escape(reHash));
	}

	/** goResult 가 KSPayWeb form 을 제출한다 — sndAmount 등 form 값은 쓰지 않고 주문번호·결제키만 받는다 */
	@PostMapping("/kspay/result")
	public ResponseEntity<Void> result(@RequestParam String sndOrdernumber, @RequestParam(required = false) String reCommConId) {
		try {
			ksPayExampleService.approve(sndOrdernumber, reCommConId);
			return redirect(UriComponentsBuilder.fromPath("/result.html").queryParam("orderNo", sndOrdernumber));
		} catch (CoreException e) {
			return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("code", e.getErrorCode().code())
												.queryParam("message", e.getMessage()));
		}
	}

	/** 화면의 mcancel() 이 부른다 — 사용자가 결제창을 닫음 */
	@PostMapping("/api/kspay/orders/user-cancel")
	public ApiResponse<Void> userCancel(@RequestBody KsPayExampleDto.CancelRequest request) {
		ksPayExampleService.markCanceledByUser(request.getOrderNo());
		return ApiResponse.success(null);
	}

	@GetMapping("/api/kspay/orders/detail")
	public ApiResponse<KsPayExampleDto.OrderDetail> detail(@RequestParam String orderNo) {
		return ApiResponse.success(ksPayExampleService.detail(orderNo));
	}

	@PostMapping("/api/kspay/orders/cancel")
	public ApiResponse<KsPayExampleDto.OrderDetail> cancel(@RequestBody KsPayExampleDto.CancelRequest request) {
		return ApiResponse.success(ksPayExampleService.cancel(request));
	}

	private static String escape(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value);
	}

	private static ResponseEntity<Void> redirect(UriComponentsBuilder location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location.encode().build().toUriString())).build();
	}
}
