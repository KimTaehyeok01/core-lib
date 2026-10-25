package com.corelib.payment.toss;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.lang.StringUtils;
import com.corelib.webclient.WebClientUtils;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 토스페이먼츠 결제 연동 진입점 — 무상태, 기대 주문번호/금액은 호출측이 보관한다.
 * <ul>
 *   <li>승인 성공 응답은 주문번호·금액·상태를 기대값과 대조한다 — 불일치는 승인이 성립한 상태라 취소가 필요하다</li>
 *   <li>결과불명(읽기 timeout·5xx)이면 승인을 재전송하지 않고 조회 API 로 실제 상태를 확인한다</li>
 *   <li>취소는 결과불명이어도 자동 재시도하지 않는다 — 이중취소 위험</li>
 *   <li>토스 4xx 오류코드는 {@code TOSS_{code}} 로 그대로 넘긴다</li>
 *   <li>동기 호출이라 한 번에 최대 connect-timeout + read-timeout + 5초 스레드를 붙잡는다 —
 *       {@code @Transactional}·DB 커넥션 점유 구간 밖에서 호출한다</li>
 * </ul>
 */
@Slf4j
public final class TossPayUtils {

	/** 승인이 성립한 상태 — 가상계좌는 발급 성공일 뿐 입금 전이다 */
	private static final Set<TossPayDto.Status> APPROVED =
		EnumSet.of(TossPayDto.Status.DONE, TossPayDto.Status.WAITING_FOR_DEPOSIT);

	/** 승인이 성립하지 않았음이 확정된 상태 */
	private static final Set<TossPayDto.Status> NOT_APPROVED =
		EnumSet.of(TossPayDto.Status.ABORTED, TossPayDto.Status.EXPIRED, TossPayDto.Status.CANCELED);

	/** 클라이언트·전체 상한을 한 번에 바꾼다 — 호출 중인 스레드가 섞인 조합을 보지 않게 */
	private static volatile Config config;

	private TossPayUtils() {
	}

	/**
	 * 기동 시 {@link TossPayInitialize} 가 1회 호출한다 — 소비자가 직접 부르지 않는다.
	 *
	 * @param callTimeout {@code .block()} 전체 상한 — 초과는 읽기 timeout 과 같은 결과불명
	 */
	static void initialize(WebClient client, Duration callTimeout) {
		config = new Config(client, callTimeout);
	}

	/**
	 * 결제를 승인한다.
	 *
	 * @return 검증된 결제 — 가상계좌는 {@code isWaitingForDeposit()} 이 true 이며 입금 전이다
	 * @throws CoreException BAD_REQUEST(요청 검증) / TOSS_{code}(토스 거절) /
	 *                       ORDER_MISMATCH·AMOUNT_MISMATCH(승인 성립, 취소 필요) /
	 *                       NOT_APPROVED(재조회 결과 미성립) / RESULT_UNKNOWN(관리자 확인)
	 */
	public static TossPayDto.Payment confirm(TossPayDto.ConfirmRequest request) {
		Config current = config();
		ValidateUtils.validate(request);

		TossPayDto.Payment payment;
		try {
			payment = WebClientUtils.post(current.webClient(), "/v1/payments/confirm", null, request, TossPayDto.Payment.class)
									.timeout(current.callTimeout())
									.block();
		} catch (RuntimeException e) {
			CoreException confirmed = toConfirmedError(e);
			if (confirmed != null) {
				throw confirmed.addErrorLogData("orderId", request.getOrderId());
			}
			// 승인 재전송은 토스가 ALREADY_PROCESSED 로 막아 의미가 없다 — 실제 상태를 조회한다.
			log.warn("토스 승인 결과불명 - 재조회로 확인. orderId={}, 사유={}", request.getOrderId(), e.getClass().getSimpleName());
			payment = recheck(request, e);
		}

		verify(payment, request);
		log.info("토스 승인 완료. orderId={}, paymentKey={}, status={}",
			request.getOrderId(), payment.getPaymentKey(), payment.getStatus());
		return payment;
	}

	/**
	 * 결제를 취소한다(전액/부분). 결과불명이어도 자동 재시도하지 않는다.
	 *
	 * @param paymentKey 승인 응답의 paymentKey
	 * @return 취소 후 결제 — {@code balanceAmount} 로 남은 금액을 확인한다
	 * @throws CoreException BAD_REQUEST / TOSS_{code}(취소 거절, 예: ALREADY_CANCELED_PAYMENT) /
	 *                       RESULT_UNKNOWN(관리자 확인 — 재시도 시 이중취소 위험)
	 */
	public static TossPayDto.Payment cancel(String paymentKey, TossPayDto.CancelRequest request) {
		Config current = config();
		requirePaymentKey(paymentKey);
		ValidateUtils.validate(request);

		TossPayDto.Payment payment;
		try {
			payment = WebClientUtils.post(current.webClient(), "/v1/payments/{paymentKey}/cancel", null, request,
				TossPayDto.Payment.class, paymentKey).timeout(current.callTimeout()).block();
		} catch (RuntimeException e) {
			CoreException confirmed = toConfirmedError(e);
			if (confirmed != null) {
				throw confirmed.addErrorLogData("paymentKey", paymentKey);
			}
			log.error("토스 취소 결과불명 - 재시도 없음, 관리자 확인 필요. paymentKey={}", paymentKey);
			throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 취소 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", e)
				.addErrorLogData("paymentKey", paymentKey);
		}

		if (payment == null) {
			throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 취소 응답이 비어 있습니다.")
				.addErrorLogData("paymentKey", paymentKey);
		}
		log.info("토스 취소 완료. paymentKey={}, status={}, balanceAmount={}",
			paymentKey, payment.getStatus(), payment.getBalanceAmount());
		return payment;
	}

	/**
	 * 결제를 조회한다 — 결과불명 승인 건 확인, 웹훅 검증 등에 쓴다.
	 *
	 * @throws CoreException BAD_REQUEST / TOSS_{code}(예: NOT_FOUND_PAYMENT) / RESULT_UNKNOWN(통신 오류)
	 */
	public static TossPayDto.Payment getPayment(String paymentKey) {
		Config current = config();
		requirePaymentKey(paymentKey);

		TossPayDto.Payment payment;
		try {
			payment = WebClientUtils.get(current.webClient(), "/v1/payments/{paymentKey}", null, TossPayDto.Payment.class, paymentKey)
									.timeout(current.callTimeout())
									.block();
		} catch (RuntimeException e) {
			CoreException confirmed = toConfirmedError(e);
			if (confirmed != null) {
				throw confirmed.addErrorLogData("paymentKey", paymentKey);
			}
			throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 조회 중 통신 오류가 발생했습니다.", e)
				.addErrorLogData("paymentKey", paymentKey);
		}

		if (payment == null) {
			throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 조회 응답이 비어 있습니다.")
				.addErrorLogData("paymentKey", paymentKey);
		}
		return payment;
	}

	/** 결과불명 승인 건을 조회해 성립 여부를 확정한다 — 조회도 실패하면 RESULT_UNKNOWN */
	private static TossPayDto.Payment recheck(TossPayDto.ConfirmRequest request, RuntimeException confirmError) {
		TossPayDto.Payment payment;
		try {
			payment = getPayment(request.getPaymentKey());
		} catch (CoreException queryError) {
			log.error("토스 승인 재조회 실패 - 관리자 확인 필요. orderId={}", request.getOrderId());
			CoreException unknown = new CoreException(TossPayError.RESULT_UNKNOWN,
				"결제 승인 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.", confirmError);
			unknown.addSuppressed(queryError);
			throw unknown.addErrorLogData("orderId", request.getOrderId())
						 .addErrorLogData("paymentKey", request.getPaymentKey());
		}

		TossPayDto.Status status = payment.getPaymentStatus();
		if (status != null && APPROVED.contains(status)) {
			log.info("토스 승인 재조회 - 승인 확인. orderId={}, status={}", request.getOrderId(), status);
			return payment;
		}
		if (status != null && NOT_APPROVED.contains(status)) {
			throw new CoreException(TossPayError.NOT_APPROVED, "결제가 승인되지 않았습니다. 다시 시도해 주세요.")
				.addErrorLogData("orderId", request.getOrderId())
				.addErrorLogData("status", payment.getStatus());
		}
		throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 승인 결과를 확인할 수 없습니다. 관리자 확인이 필요합니다.")
			.addErrorLogData("orderId", request.getOrderId())
			.addErrorLogData("status", payment.getStatus());
	}

	/** 승인 응답의 주문번호/금액/상태를 기대값과 대조 — 금액·주문번호 불일치는 승인이 성립한 상태라 취소가 필요하다 */
	private static void verify(TossPayDto.Payment payment, TossPayDto.ConfirmRequest request) {
		if (payment == null) {
			throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 승인 응답이 비어 있습니다.")
				.addErrorLogData("orderId", request.getOrderId());
		}
		if (!request.getOrderId().equals(payment.getOrderId())) {
			throw new CoreException(TossPayError.ORDER_MISMATCH, "결제 주문번호 불일치 - 승인 취소가 필요합니다.")
				.addErrorLogData("paymentKey", payment.getPaymentKey())
				.addErrorLogData("expectedOrderId", request.getOrderId())
				.addErrorLogData("actualOrderId", payment.getOrderId());
		}
		if (!request.getAmount().equals(payment.getTotalAmount())) {
			throw new CoreException(TossPayError.AMOUNT_MISMATCH, "결제 금액 불일치 - 승인 취소가 필요합니다.")
				.addErrorLogData("paymentKey", payment.getPaymentKey())
				.addErrorLogData("orderId", request.getOrderId())
				.addErrorLogData("expectedAmount", request.getAmount())
				.addErrorLogData("actualAmount", payment.getTotalAmount());
		}
		TossPayDto.Status status = payment.getPaymentStatus();
		if (status == null || !APPROVED.contains(status)) {
			throw new CoreException(TossPayError.RESULT_UNKNOWN, "결제 승인 상태를 확인할 수 없습니다.")
				.addErrorLogData("orderId", request.getOrderId())
				.addErrorLogData("status", payment.getStatus());
		}
	}

	/**
	 * 결과확정 오류면 CoreException 으로, 결과불명이면 null 로 돌려준다 — 분류 기준은 {@link WebClientUtils#classify}.
	 * 4xx 는 본문 code 를 {@code TOSS_{code}} 로 넘기고, 연결 실패는 요청 미전송이라 결과확정이다.
	 */
	private static CoreException toConfirmedError(RuntimeException error) {
		return switch (WebClientUtils.classify(error)) {
			case REJECTED -> vendorError(WebClientUtils.findClientError(error).orElseThrow());
			case NOT_SENT -> new CoreException(TossPayError.EXTERNAL_ERROR, "결제 서버에 연결할 수 없습니다.", error);
			case UNKNOWN -> null;
		};
	}

	/**
	 * 4xx 본문 {code, message} 를 {@code TOSS_{code}} 로 바꾼다 — 본문이 토스 규격이 아니면 EXTERNAL_ERROR.
	 * @implNote 401(시크릿 키 오류)은 소비 앱 설정 문제라 사용자에게 500 으로 내보낸다
	 */
	private static CoreException vendorError(WebClientResponseException e) {
		TossPayDto.ErrorResponse body = null;
		try {
			body = e.getResponseBodyAs(TossPayDto.ErrorResponse.class);
		} catch (RuntimeException ignored) {
			// 토스 규격 본문이 아니다
		}
		int status = e.getStatusCode().value();
		if (body == null || StringUtils.isBlank(body.getCode())) {
			return new CoreException(TossPayError.EXTERNAL_ERROR, "토스페이먼츠 오류 응답입니다.")
				.addErrorLogData("httpStatus", status);
		}
		int responseStatus = status == 401 ? 500 : status;
		String message = StringUtils.defaultIfBlank(body.getMessage(), "토스페이먼츠 오류 응답입니다.");
		return new CoreException(ErrorCode.of("TOSS_" + body.getCode(), message, responseStatus), message)
			.addErrorLogData("httpStatus", status);
	}

	private static void requirePaymentKey(String paymentKey) {
		if (StringUtils.isBlank(paymentKey)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "paymentKey 가 없습니다.");
		}
	}

	/** 초기화 여부를 확인한다 — secret-key 미설정이면 자동설정이 꺼져 있어 여기서 드러난다 */
	private static Config config() {
		Config current = config;
		if (current == null) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "TossPayUtils 미초기화 - payment.toss.secret-key 설정 확인");
		}
		return current;
	}

	/** 기동 시 정해지는 호출 설정 — 불변 */
	private record Config(WebClient webClient, Duration callTimeout) {
	}
}
