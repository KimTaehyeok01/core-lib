package com.corelib.example.payment.toss.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.payment.toss.dto.TossExampleDto;
import com.corelib.lang.RandomStringUtils;
import com.corelib.lang.StringUtils;
import com.corelib.payment.toss.TossPayDto;
import com.corelib.payment.toss.TossPayError;
import com.corelib.payment.toss.TossPayUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 토스 결제 예제 서비스.
 * <ul>
 *   <li>금액은 주문 생성 때 서버가 정하고, 승인에는 서버가 보관한 금액을 쓴다 — successUrl 의 amount 는 위변조 확인용</li>
 *   <li>승인됐는데 주문번호·금액이 다르면 즉시 취소한다</li>
 *   <li>결과불명(RESULT_UNKNOWN)은 실패로 단정하지 않고 UNKNOWN 으로 남겨 관리자가 확인한다</li>
 *   <li>승인·취소는 상태를 원자 선점(READY→PROCESSING, →CANCELING)한 요청만 토스를 호출하고, 결과는 선점한 상태일 때만 기록한다</li>
 *   <li>콜백은 서명 없는 브라우저 입력이라 failUrl·금액 불일치·토스 승인 거절로는 주문을 FAILED 로 확정하지 않는다</li>
 * </ul>
 */
@Slf4j
@Service
public class TossExampleService {

	private static final DateTimeFormatter ORDER_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	/**
	 * 실서비스는 주문 테이블 — 예제라 메모리에 두고, 상태 전이는 {@link #transition} 의 replace(기대값, 새값) 로만 한다.
	 * 실서비스는 DB 조건부 UPDATE(WHERE status = ?) 로 같은 원자성 확보
	 */
	private final Map<String, TossExampleDto.OrderDetail> orders = new ConcurrentHashMap<>();
	private final String clientKey;

	public TossExampleService(@Value("${example.toss.client-key:}") String clientKey) {
		this.clientKey = clientKey;
	}

	/** 주문 생성 — orderId 는 토스 규칙(영문·숫자·-_=, 6~64자) */
	public TossExampleDto.OrderResponse createOrder(TossExampleDto.OrderRequest request) {
		ValidateUtils.validate(request);
		String orderId = "ORD-" + LocalDateTime.now().format(ORDER_ID_TIME) + "-" + RandomStringUtils.randomAlphanumeric(8);
		orders.put(orderId, TossExampleDto.OrderDetail.builder()
													  .orderId(orderId)
													  .orderName(request.getOrderName())
													  .amount(request.getAmount())
													  .status(TossExampleDto.OrderStatus.READY)
													  .build());
		return TossExampleDto.OrderResponse.builder()
										   .orderId(orderId)
										   .orderName(request.getOrderName())
										   .amount(request.getAmount())
										   .clientKey(clientKey)
										   .build();
	}

	/**
	 * successUrl 콜백 — 결제 승인. 이미 처리됐거나 다른 요청이 선점한 주문은 토스를 호출하지 않고 현재 주문을 돌려준다.
	 * @param amountFromBrowser successUrl 쿼리의 amount — 브라우저를 거친 값이라 저장된 금액과 다르면 승인하지 않는다
	 */
	public TossExampleDto.OrderDetail confirm(String paymentKey, String orderId, String amountFromBrowser) {
		Integer amount = parseAmount(amountFromBrowser);
		if (StringUtils.isBlank(paymentKey) || StringUtils.isBlank(orderId) || amount == null) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "결제 정보가 올바르지 않습니다.");
		}
		TossExampleDto.OrderDetail order = find(orderId);
		if (order.getStatus() != TossExampleDto.OrderStatus.READY) {
			return order;    // 새로고침 등으로 콜백이 다시 와도 두 번 승인하지 않는다
		}
		if (!Objects.equals(order.getAmount(), amount)) {
			// 서명 없는 브라우저 입력이라 FAILED 로 확정하지 않는다 — READY 로 남겨 정상 콜백으로 다시 승인할 수 있다
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "결제 금액이 주문과 다릅니다.").addErrorLogData("orderId", orderId);
		}
		// READY → PROCESSING 을 선점한 요청만 승인한다 — 동시 콜백의 패자는 토스를 호출하지 않는다
		TossExampleDto.OrderDetail processing = order.toBuilder().status(TossExampleDto.OrderStatus.PROCESSING).build();
		if (!transition(order, processing)) {
			return find(orderId);
		}

		try {
			TossPayDto.Payment payment = TossPayUtils.confirm(TossPayDto.ConfirmRequest.builder()
																					   .paymentKey(paymentKey)
																					   .orderId(orderId)
																					   .amount(order.getAmount())
																					   .build());
			TossExampleDto.OrderDetail paid = order.toBuilder()
												   .status(payment.isWaitingForDeposit() ? TossExampleDto.OrderStatus.WAITING_FOR_DEPOSIT
													   : TossExampleDto.OrderStatus.PAID)
												   .paymentKey(payment.getPaymentKey())
												   .method(payment.getMethod())
												   .approvedAt(payment.getApprovedAt())
												   .balanceAmount(payment.getBalanceAmount())
												   .build();
			return transition(processing, paid) ? paid : find(orderId);
		} catch (CoreException e) {
			throw handleConfirmFailure(processing, paymentKey, e);
		}
	}

	/** 전액/부분 취소 — CANCELING 을 선점한 요청만 토스 취소를 호출한다 */
	public TossExampleDto.OrderDetail cancel(TossExampleDto.CancelRequest request) {
		ValidateUtils.validate(request);
		TossExampleDto.OrderDetail order = find(request.getOrderId());
		if (order.getStatus() == TossExampleDto.OrderStatus.CANCEL_UNKNOWN) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "이전 취소 결과를 확인하고 있습니다. 관리자 확인 전에는 다시 취소할 수 없습니다.");
		}
		if (order.getPaymentKey() == null
				|| (order.getStatus() != TossExampleDto.OrderStatus.PAID && order.getStatus() != TossExampleDto.OrderStatus.PARTIAL_CANCELED)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소할 수 없는 주문 상태입니다.");
		}
		TossExampleDto.OrderDetail canceling = order.toBuilder().status(TossExampleDto.OrderStatus.CANCELING).build();
		if (!transition(order, canceling)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "다른 요청이 처리 중인 주문입니다. 주문 상태를 다시 확인해 주세요.");
		}

		try {
			TossPayDto.Payment payment = TossPayUtils.cancel(order.getPaymentKey(), TossPayDto.CancelRequest.builder()
																										   .cancelReason(request.getReason())
																										   .cancelAmount(request.getCancelAmount())
																										   .build());
			TossExampleDto.OrderDetail canceled = order.toBuilder()
													   .status(payment.getBalanceAmount() != null && payment.getBalanceAmount() > 0
														   ? TossExampleDto.OrderStatus.PARTIAL_CANCELED : TossExampleDto.OrderStatus.CANCELED)
													   .balanceAmount(payment.getBalanceAmount())
													   .build();
			return transition(canceling, canceled) ? canceled : find(order.getOrderId());
		} catch (CoreException e) {
			// 결과불명은 이미 환불됐을 수 있다 — 원래 상태로 되돌리면 재취소로 이중 환불되므로 관리자 확인 전까지 막는다
			transition(canceling, e.getErrorCode() == TossPayError.RESULT_UNKNOWN
				? canceling.toBuilder().status(TossExampleDto.OrderStatus.CANCEL_UNKNOWN).failMessage(e.getMessage()).build()
				: order);
			throw e;
		}
	}

	public TossExampleDto.OrderDetail detail(String orderId) {
		return find(orderId);
	}

	/**
	 * 승인 실패 처리 — 승인이 성립했는데 값이 다르면 즉시 취소, 결과불명은 관리자 확인으로. 기록은 선점한 PROCESSING 일 때만.
	 * @return 호출측이 던질 예외 — 토스 거절은 화면에 고정 문구를 보이도록 바꾼다
	 */
	private CoreException handleConfirmFailure(TossExampleDto.OrderDetail processing, String paymentKey, CoreException e) {
		if (e.getErrorCode() == TossPayError.AMOUNT_MISMATCH || e.getErrorCode() == TossPayError.ORDER_MISMATCH) {
			try {
				TossPayUtils.cancel(paymentKey, TossPayDto.CancelRequest.builder().cancelReason("주문 정보 불일치 자동취소").build());
				fail(processing, "주문 정보가 달라 결제를 취소했습니다.");
			} catch (CoreException cancelError) {
				log.error("토스 불일치 자동취소 실패 - 관리자 확인 필요. orderId={}", processing.getOrderId());
				transition(processing, processing.toBuilder().status(TossExampleDto.OrderStatus.UNKNOWN).paymentKey(paymentKey)
												 .failMessage("자동취소 실패 - 관리자 확인 필요").build());
			}
			return e;
		}
		if (e.getErrorCode() == TossPayError.RESULT_UNKNOWN) {
			transition(processing, processing.toBuilder().status(TossExampleDto.OrderStatus.UNKNOWN).paymentKey(paymentKey)
											 .failMessage(e.getMessage()).build());
			return e;
		}
		// 콜백에 서명이 없어 paymentKey 가 가짜일 수 있다 — 거절·미성립·요청 검증 실패는 FAILED 로 확정하지 않고 READY 로 되돌린다(재시도 가능)
		log.info("토스 승인 거절 - READY 로 되돌림. orderId={}, code={}", processing.getOrderId(), e.getErrorCode().code());
		transition(processing, processing.toBuilder().status(TossExampleDto.OrderStatus.READY).build());
		return new CoreException(e.getErrorCode(), "결제가 승인되지 않았습니다. 다시 시도해 주세요.", e);
	}

	private void fail(TossExampleDto.OrderDetail processing, String message) {
		transition(processing, processing.toBuilder().status(TossExampleDto.OrderStatus.FAILED).failMessage(message).build());
	}

	/** 읽어 둔 상태 그대로일 때만 바꾼다 — 다른 요청이 먼저 바꿨으면 false */
	private boolean transition(TossExampleDto.OrderDetail expected, TossExampleDto.OrderDetail next) {
		return orders.replace(expected.getOrderId(), expected, next);
	}

	/** successUrl 의 amount — 누락·형식 오류는 null */
	private static Integer parseAmount(String amount) {
		if (StringUtils.isBlank(amount)) {
			return null;
		}
		try {
			return Integer.valueOf(amount.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private TossExampleDto.OrderDetail find(String orderId) {
		TossExampleDto.OrderDetail order = orderId == null ? null : orders.get(orderId);
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}
		return order;
	}
}
