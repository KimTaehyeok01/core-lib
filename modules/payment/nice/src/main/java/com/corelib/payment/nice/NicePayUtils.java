package com.corelib.payment.nice;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.lang.StringUtils;
import com.corelib.webclient.FailureType;
import com.corelib.webclient.WebClientUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 나이스페이먼츠(Server 승인 모델) 연동 진입점 — 무상태, 기대 주문번호/금액은 호출측이 보관한다.
 * <ol>
 *   <li>returnUrl 로 온 인증결과를 {@link #verifyAuthResult} 로 검증한다 (코드·주문번호·금액·서명)</li>
 *   <li>돌려받은 tid 로 {@link #approve} 를 호출한다 — 이 호출이 없으면 결제는 일어나지 않는다</li>
 * </ol>
 * <ul>
 *   <li>승인 응답은 서명·주문번호·금액·상태를 대조한다 — 불일치는 승인이 성립한 상태라 취소가 필요하다</li>
 *   <li>승인 결과불명(읽기 timeout·5xx)이면 매뉴얼 지침대로 즉시 망취소한다 — 망취소는 승인 요청 후 1시간 안에만 된다</li>
 *   <li>취소는 결과불명이어도 자동 재시도하지 않는다 — 이중취소 위험</li>
 *   <li>NICEPAY 결과코드는 {@code NICEPAY_{resultCode}} 로 그대로 넘긴다</li>
 * </ul>
 */
@Slf4j
public final class NicePayUtils {

	private static final String SUCCESS_CODE = "0000";
	/** 매뉴얼 규격 Content-type: application/json;charset=utf-8 */
	private static final Consumer<HttpHeaders> JSON_UTF8 =
		headers -> headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

	private static WebClient webClient;
	private static NicePayProperty nicePayProperty;

	private NicePayUtils() {
	}

	/** 기동 시 {@link NicePayInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다 */
	static void initialize(WebClient initializeWebClient, NicePayProperty initializeNicePayProperty) {
		webClient = initializeWebClient;
		nicePayProperty = initializeNicePayProperty;
	}

	/**
	 * 결제창 인증결과(returnUrl POST)를 검증한다 — 통과해야만 승인을 호출한다.
	 *
	 * @param expectedOrderId 호출측이 보관한 주문번호
	 * @param expectedAmount  호출측이 보관한 결제 금액
	 * @return 승인에 쓸 tid
	 * @throws CoreException NICEPAY_{authResultCode}(인증 실패·사용자 취소) / INVALID_AUTH_RESULT(필수값 누락) /
	 *                       AUTH_MISMATCH(클라이언트키·주문번호·금액 불일치) / SIGNATURE_MISMATCH(위변조)
	 */
	public static String verifyAuthResult(NicePayDto.AuthResult authResult, String expectedOrderId, Integer expectedAmount) {
		NicePayProperty property = property();
		if (authResult == null) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "결제 인증 결과가 없습니다.");
		}
		final Map<String, Object> logData = Map.of("orderId", String.valueOf(authResult.getOrderId()));

		if (!SUCCESS_CODE.equals(authResult.getAuthResultCode())) {
			String message = StringUtils.defaultIfBlank(authResult.getAuthResultMsg(), "결제 인증에 실패했습니다.");
			String code = StringUtils.defaultIfBlank(authResult.getAuthResultCode(), "AUTH_FAILED");
			throw new CoreException(ErrorCode.of("NICEPAY_" + code, message, 400), message).addErrorLogData(logData);
		}
		if (StringUtils.isBlank(authResult.getTid()) || StringUtils.isBlank(authResult.getAuthToken())
				|| StringUtils.isBlank(authResult.getSignature()) || authResult.getAmount() == null) {
			throw new CoreException(NicePayError.INVALID_AUTH_RESULT, "결제 인증 결과 필수값이 없습니다.").addErrorLogData(logData);
		}
		// 브라우저를 거쳐 온 값이라 우리 키·주문과 일치하는지 먼저 본다.
		if (!property.getClientKey().equals(authResult.getClientId())
				|| !Objects.equals(expectedOrderId, authResult.getOrderId())
				|| !Objects.equals(expectedAmount, authResult.getAmount())) {
			throw new CoreException(NicePayError.AUTH_MISMATCH, "결제 인증 정보가 주문과 다릅니다.")
				.addErrorLogData(logData)
				.addErrorLogData("expectedAmount", expectedAmount)
				.addErrorLogData("actualAmount", authResult.getAmount());
		}
		String expectedSignature = NicePaySignature.ofAuthResult(authResult.getAuthToken(), authResult.getClientId(),
			authResult.getAmount(), property.getSecretKey());
		if (!NicePaySignature.matches(expectedSignature, authResult.getSignature())) {
			throw new CoreException(NicePayError.SIGNATURE_MISMATCH, "결제 인증 결과 위변조가 의심됩니다.").addErrorLogData(logData);
		}
		return authResult.getTid();
	}

	/**
	 * 결제를 승인한다 — 결과불명이면 망취소까지 수행한다.
	 *
	 * @return 검증된 결제 — 가상계좌는 {@code isVbankReady()} 가 true 이며 입금 전이다
	 * @throws CoreException BAD_REQUEST / NICEPAY_{resultCode}(승인 거절) /
	 *                       SIGNATURE_MISMATCH·ORDER_MISMATCH·AMOUNT_MISMATCH(승인 성립 가능, 취소 필요) /
	 *                       NET_CANCELLED(결과불명 → 망취소 완료, 결제 미성립) / RESULT_UNKNOWN(망취소도 실패, 관리자 확인)
	 */
	public static NicePayDto.Payment approve(NicePayDto.ApproveRequest request) {
		WebClient client = client();
		ValidateUtils.validate(request);
		final Map<String, Object> logData = Map.of("orderId", request.getOrderId(), "tid", request.getTid());

		NicePayDto.Payment payment;
		try {
			payment = WebClientUtils.post(client, "/v1/payments/{tid}", JSON_UTF8, Map.of("amount", request.getAmount()),
				NicePayDto.Payment.class, request.getTid()).block();
		} catch (RuntimeException e) {
			FailureType failureType = WebClientUtils.classify(e);
			if (failureType == FailureType.REJECTED) {
				throw vendorError(e).addErrorLogData(logData);
			}
			if (failureType == FailureType.NOT_SENT) {
				throw new CoreException(NicePayError.EXTERNAL_ERROR, "결제 서버에 연결할 수 없습니다.", e).addErrorLogData(logData);
			}
			log.warn("NICEPAY 승인 결과불명 - 망취소 진행. orderId={}, tid={}, 사유={}",
				request.getOrderId(), request.getTid(), e.getClass().getSimpleName());
			throw netCancelAfterUnknown(request, e);
		}

		// 200 인데 본문이 없으면 승인 여부를 알 수 없다 — timeout 과 같게 망취소한다.
		if (payment == null) {
			log.warn("NICEPAY 승인 응답 본문 없음 - 망취소 진행. orderId={}, tid={}", request.getOrderId(), request.getTid());
			throw netCancelAfterUnknown(request, null);
		}
		if (!payment.isSuccess()) {
			throw rejected(payment).addErrorLogData(logData);
		}

		verifyApproved(payment, request);
		log.info("NICEPAY 승인 완료. orderId={}, tid={}, status={}, payMethod={}",
			request.getOrderId(), payment.getTid(), payment.getStatus(), payment.getPayMethod());
		return payment;
	}

	/**
	 * 망취소 — 승인 응답을 못 받았을 때 승인 여부와 무관하게 거래를 되돌린다. 승인 요청 후 1시간 안에만 된다.
	 * 일반 취소는 {@link #cancel} 을 쓴다.
	 *
	 * @throws CoreException BAD_REQUEST / NICEPAY_{resultCode}(망취소 거절) / RESULT_UNKNOWN / EXTERNAL_ERROR
	 */
	public static NicePayDto.Payment netCancel(String orderId) {
		WebClient client = client();
		if (StringUtils.isBlank(orderId)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "orderId 가 없습니다.");
		}
		NicePayDto.Payment payment = call(() -> WebClientUtils.post(client, "/v1/payments/netcancel", JSON_UTF8,
				Map.of("orderId", orderId), NicePayDto.Payment.class).block(),
			"망취소", Map.of("orderId", orderId));
		log.info("NICEPAY 망취소 완료. orderId={}, tid={}", orderId, payment.getTid());
		return payment;
	}

	/**
	 * 결제를 취소한다(전액/부분). 결과불명이어도 자동 재시도하지 않는다.
	 *
	 * @param tid 승인 응답의 tid
	 * @return 취소 후 결제 — {@code balanceAmt} 로 남은 금액, {@code cancelledTid} 로 이번 취소 건을 확인한다
	 * @throws CoreException BAD_REQUEST / NICEPAY_{resultCode}(취소 거절) / RESULT_UNKNOWN(관리자 확인 — 재시도 시 이중취소 위험)
	 */
	public static NicePayDto.Payment cancel(String tid, NicePayDto.CancelRequest request) {
		WebClient client = client();
		requireTid(tid);
		ValidateUtils.validate(request);

		NicePayDto.Payment payment = call(() -> WebClientUtils.post(client, "/v1/payments/{tid}/cancel", JSON_UTF8, request,
				NicePayDto.Payment.class, tid).block(),
			"취소", Map.of("tid", tid, "orderId", request.getOrderId()));
		log.info("NICEPAY 취소 완료. tid={}, cancelledTid={}, status={}, balanceAmt={}",
			tid, payment.getCancelledTid(), payment.getStatus(), payment.getBalanceAmt());
		return payment;
	}

	/**
	 * 거래를 조회한다 — 웹훅 검증, 데이터 유실 확인 등에 쓴다.
	 *
	 * @throws CoreException BAD_REQUEST / NICEPAY_{resultCode} / RESULT_UNKNOWN(통신 오류)
	 */
	public static NicePayDto.Payment getPayment(String tid) {
		WebClient client = client();
		requireTid(tid);
		return call(() -> WebClientUtils.get(client, "/v1/payments/{tid}", null, NicePayDto.Payment.class, tid).block(),
			"조회", Map.of("tid", tid));
	}

	/**
	 * 결과불명 승인 건을 망취소한다 — 성공하면 결제 미성립으로 확정(NET_CANCELLED), 실패하면 RESULT_UNKNOWN.
	 * @implNote 승인이 실제로 안 났던 건의 망취소 응답 코드는 매뉴얼에 없어 실패로 보고 관리자 확인으로 돌린다(보수적 처리)
	 */
	private static CoreException netCancelAfterUnknown(NicePayDto.ApproveRequest request, Throwable approveError) {
		NicePayDto.Payment netCancelled = null;
		CoreException netCancelError = null;
		try {
			netCancelled = netCancel(request.getOrderId());
		} catch (CoreException e) {
			netCancelError = e;
		}

		if (netCancelled != null) {
			return new CoreException(NicePayError.NET_CANCELLED, "결제 응답을 받지 못해 결제를 취소했습니다. 다시 시도해 주세요.", approveError)
				.addErrorLogData("orderId", request.getOrderId())
				.addErrorLogData("tid", request.getTid());
		}
		log.error("NICEPAY 승인 결과불명 + 망취소 실패 - 관리자 확인 필요. orderId={}, tid={}", request.getOrderId(), request.getTid());
		CoreException unknown = new CoreException(NicePayError.RESULT_UNKNOWN,
			"결제 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", approveError);
		if (netCancelError != null) {
			unknown.addSuppressed(netCancelError);
		}
		return unknown.addErrorLogData("orderId", request.getOrderId()).addErrorLogData("tid", request.getTid());
	}

	/** 승인 응답의 서명 → 주문번호 → 금액 → 상태 순으로 대조한다 — 서명이 틀리면 나머지 값도 믿을 수 없어 먼저 본다 */
	private static void verifyApproved(NicePayDto.Payment payment, NicePayDto.ApproveRequest request) {
		final Map<String, Object> logData = Map.of("orderId", request.getOrderId(), "tid", String.valueOf(payment.getTid()));

		// 서명은 유효 거래에만 온다(매뉴얼) — 비어 있으면 검증을 건너뛰고 아래 대조로 판단한다.
		if (StringUtils.isNotBlank(payment.getSignature())) {
			String expected = NicePaySignature.ofPayment(payment.getTid(), payment.getAmount(), payment.getEdiDate(),
				nicePayProperty.getSecretKey());
			if (!NicePaySignature.matches(expected, payment.getSignature())) {
				throw new CoreException(NicePayError.SIGNATURE_MISMATCH, "결제 응답 위변조가 의심됩니다. 취소가 필요합니다.")
					.addErrorLogData(logData);
			}
		}
		if (!request.getOrderId().equals(payment.getOrderId())) {
			throw new CoreException(NicePayError.ORDER_MISMATCH, "결제 주문번호 불일치 - 승인 취소가 필요합니다.")
				.addErrorLogData(logData)
				.addErrorLogData("actualOrderId", payment.getOrderId());
		}
		if (!request.getAmount().equals(payment.getAmount())) {
			throw new CoreException(NicePayError.AMOUNT_MISMATCH, "결제 금액 불일치 - 승인 취소가 필요합니다.")
				.addErrorLogData(logData)
				.addErrorLogData("expectedAmount", request.getAmount())
				.addErrorLogData("actualAmount", payment.getAmount());
		}
		if (!payment.isPaid() && !payment.isVbankReady()) {
			throw new CoreException(NicePayError.RESULT_UNKNOWN, "결제 승인 상태를 확인할 수 없습니다.")
				.addErrorLogData(logData)
				.addErrorLogData("status", String.valueOf(payment.getStatus()));
		}
	}

	/** 호출 후 resultCode 0000 을 확인한다 — 결과불명은 RESULT_UNKNOWN, 거절은 NICEPAY_{resultCode} */
	private static NicePayDto.Payment call(Supplier<NicePayDto.Payment> request, String action, Map<String, ?> logData) {
		NicePayDto.Payment payment;
		try {
			payment = request.get();
		} catch (RuntimeException e) {
			CoreException error = switch (WebClientUtils.classify(e)) {
				case REJECTED -> vendorError(e);
				case NOT_SENT -> new CoreException(NicePayError.EXTERNAL_ERROR, "결제 서버에 연결할 수 없습니다.", e);
				case UNKNOWN -> new CoreException(NicePayError.RESULT_UNKNOWN,
					"결제 " + action + " 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", e);
			};
			throw error.addErrorLogData(logData);
		}
		if (payment == null) {
			throw new CoreException(NicePayError.RESULT_UNKNOWN, "결제 " + action + " 응답이 비어 있습니다.").addErrorLogData(logData);
		}
		if (!payment.isSuccess()) {
			throw rejected(payment).addErrorLogData(logData);
		}
		return payment;
	}

	/** NICEPAY 결과코드를 그대로 넘긴다 — 메시지는 사용자에게 보여줄 수 있는 NICEPAY 원문 */
	private static CoreException rejected(NicePayDto.Payment payment) {
		String message = StringUtils.defaultIfBlank(payment.getResultMsg(), "나이스페이먼츠 처리에 실패했습니다.");
		return new CoreException(ErrorCode.of("NICEPAY_" + payment.getResultCode(), message, 400), message);
	}

	/** 4xx 본문의 resultCode 를 살린다 — 본문이 NICEPAY 규격이 아니면 EXTERNAL_ERROR */
	private static CoreException vendorError(Throwable error) {
		WebClientResponseException clientError = WebClientUtils.findClientError(error).orElse(null);
		NicePayDto.Payment body = null;
		if (clientError != null) {
			try {
				body = clientError.getResponseBodyAs(NicePayDto.Payment.class);
			} catch (RuntimeException ignored) {
				// NICEPAY 규격 본문이 아니다
			}
		}
		if (body == null || StringUtils.isBlank(body.getResultCode())) {
			return new CoreException(NicePayError.EXTERNAL_ERROR, "나이스페이먼츠 오류 응답입니다.")
				.addErrorLogData("httpStatus", clientError == null ? null : clientError.getStatusCode().value());
		}
		return rejected(body);
	}

	private static void requireTid(String tid) {
		if (StringUtils.isBlank(tid)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "tid 가 없습니다.");
		}
	}

	/** 초기화 여부를 확인한다 — client-key 미설정이면 자동설정이 꺼져 있어 여기서 드러난다 */
	private static WebClient client() {
		if (webClient == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "NicePayUtils 미초기화 - payment.nice.client-key 설정 확인");
		}
		return webClient;
	}

	private static NicePayProperty property() {
		client();
		return nicePayProperty;
	}
}
