package com.corelib.example.payment.kspay.controller;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.payment.kspay.dto.KsPayExampleDto;
import com.corelib.example.payment.kspay.service.KsPayExampleService;
import com.corelib.lang.StringUtils;
import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
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

	/** 결과 화면으로 보낼 상태 — 결제가 진행·성립했을 수 있는 모든 상태(처리 중·결과불명 포함). FAILED 만 실패 화면 */
	private static final Set<KsPayExampleDto.OrderStatus> RESULT_PAGE_STATUSES = EnumSet.of(KsPayExampleDto.OrderStatus.PROCESSING,
		KsPayExampleDto.OrderStatus.PAID, KsPayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT, KsPayExampleDto.OrderStatus.PARTIAL_CANCELED,
		KsPayExampleDto.OrderStatus.CANCELING, KsPayExampleDto.OrderStatus.CANCELED, KsPayExampleDto.OrderStatus.CANCEL_UNKNOWN,
		KsPayExampleDto.OrderStatus.UNKNOWN);

	private final KsPayExampleService ksPayExampleService;

	@PostMapping("/api/kspay/orders/create")
	public ApiResponse<KsPayExampleDto.OrderResponse> createOrder(@RequestBody KsPayExampleDto.OrderRequest request) {
		return ApiResponse.success(ksPayExampleService.createOrder(request));
	}

	/**
	 * sndReply — 결제창이 결과를 form POST 한다 (공식 샘플 kspay_wh_rcv).
	 * 승인은 여기서 하지 않고 opener 로 결제키만 넘긴다. 값은 HTML 이스케이프해 data 속성으로만 전달한다.
	 * 취소도 아닌데 결제키가 없으면 결제창 결과가 깨진 것이라 실패 화면으로 보낸다.
	 */
	@PostMapping(value = "/kspay/rcv", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> receive(@RequestParam(required = false) String reCommConId, @RequestParam(required = false) String reCommType,
										  @RequestParam(required = false) String reHash, @RequestParam(required = false) String reCnclType) {
		boolean isCanceled = CANCELED_BY_USER.equals(reCnclType);
		if (!isCanceled && StringUtils.isBlank(reCommConId)) {
			return fail(CommonErrorCode.BAD_REQUEST.code(), "결제 결과를 받지 못했습니다. 다시 결제해 주세요.");
		}
		return ResponseEntity.ok("""
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
			""".formatted(isCanceled, escape(reCommConId), escape(reCommType), escape(reHash)));
	}

	/**
	 * goResult 가 KSPayWeb form 을 제출한다 — sndAmount 등 form 값은 쓰지 않고 주문번호·결제키만 받는다.
	 * 파라미터 누락·형식 오류도 JSON 400 이 아니라 실패 화면으로 보낸다. 다시 온 콜백은 주문 상태로 화면을 고른다.
	 */
	@PostMapping("/kspay/result")
	public ResponseEntity<Void> result(@RequestParam(required = false) String sndOrdernumber, @RequestParam(required = false) String reCommConId) {
		try {
			KsPayExampleDto.OrderDetail order = ksPayExampleService.approve(sndOrdernumber, reCommConId);
			if (RESULT_PAGE_STATUSES.contains(order.getStatus())) {
				return redirect(UriComponentsBuilder.fromPath("/result.html").queryParam("orderNo", order.getOrderNo()));
			}
			return fail(order.getStatus().name(), StringUtils.defaultIfBlank(order.getFailMessage(), "결제가 완료되지 않았습니다."));
		} catch (CoreException e) {
			return fail(e.getErrorCode().code(), e.getMessage());
		}
	}

	/** 화면의 mcancel() 이 부른다 — 사용자가 결제창을 닫음. 서명 없는 브라우저 신호라 주문은 READY 로 둔다 */
	@PostMapping("/api/kspay/orders/user-cancel")
	public ApiResponse<Void> userCancel(@RequestBody KsPayExampleDto.CancelRequest request) {
		ksPayExampleService.markCanceledByUser(request.getOrderNo());
		return ApiResponse.success(null);
	}

	/** 주문 상세 — 예제는 인증 없음. 실서비스는 주문 소유자/관리자 확인 필수 */
	@GetMapping("/api/kspay/orders/detail")
	public ApiResponse<KsPayExampleDto.OrderDetail> detail(@RequestParam String orderNo) {
		return ApiResponse.success(ksPayExampleService.detail(orderNo));
	}

	/** 전액/부분 취소 — 예제는 인증 없음. 실서비스는 주문 소유자/관리자 확인 필수 */
	@PostMapping("/api/kspay/orders/cancel")
	public ApiResponse<KsPayExampleDto.OrderDetail> cancel(@RequestBody KsPayExampleDto.CancelRequest request) {
		return ApiResponse.success(ksPayExampleService.cancel(request));
	}

	private static String escape(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value);
	}

	private static <T> ResponseEntity<T> fail(String code, String message) {
		return redirect(UriComponentsBuilder.fromPath("/fail.html").queryParam("code", code).queryParam("message", message));
	}

	private static <T> ResponseEntity<T> redirect(UriComponentsBuilder location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location.encode().build().toUriString())).build();
	}
}
