// TossPayUtils 로 주문 결제 승인·취소를 처리하는 예제 서비스 (주문은 메모리 보관)
package com.corelib.example.payment.toss.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.payment.toss.dto.TossExampleDto;
import com.corelib.lang.RandomStringUtils;
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
 * </ul>
 */
@Slf4j
@Service
public class TossExampleService {

	private static final DateTimeFormatter ORDER_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	/** 실서비스는 주문 테이블 — 예제라 메모리에 둔다 */
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
	 * successUrl 콜백 — 결제 승인.
	 * @param amountFromBrowser successUrl 쿼리의 amount — 브라우저를 거친 값이라 저장된 금액과 다르면 승인하지 않는다
	 */
	public TossExampleDto.OrderDetail confirm(String paymentKey, String orderId, Integer amountFromBrowser) {
		TossExampleDto.OrderDetail order = find(orderId);
		if (order.getStatus() != TossExampleDto.OrderStatus.READY) {
			return order;    // 새로고침 등으로 콜백이 다시 와도 두 번 승인하지 않는다
		}
		if (!Objects.equals(order.getAmount(), amountFromBrowser)) {
			fail(order, "결제 금액이 주문과 다릅니다.");
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "결제 금액이 주문과 다릅니다.").addErrorLogData("orderId", orderId);
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
			orders.put(orderId, paid);
			return paid;
		} catch (CoreException e) {
			handleConfirmFailure(order, paymentKey, e);
			throw e;
		}
	}

	/** failUrl 콜백 — 사용자 취소(PAY_PROCESS_CANCELED)는 orderId 가 없다 */
	public void markFailed(String orderId, String message) {
		if (orderId != null && orders.containsKey(orderId)) {
			TossExampleDto.OrderDetail order = orders.get(orderId);
			if (order.getStatus() == TossExampleDto.OrderStatus.READY) {
				fail(order, message);
			}
		}
	}

	/** 전액/부분 취소 */
	public TossExampleDto.OrderDetail cancel(TossExampleDto.CancelRequest request) {
		ValidateUtils.validate(request);
		TossExampleDto.OrderDetail order = find(request.getOrderId());
		if (order.getPaymentKey() == null
				|| (order.getStatus() != TossExampleDto.OrderStatus.PAID && order.getStatus() != TossExampleDto.OrderStatus.PARTIAL_CANCELED)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소할 수 없는 주문 상태입니다.");
		}
		TossPayDto.Payment payment = TossPayUtils.cancel(order.getPaymentKey(), TossPayDto.CancelRequest.builder()
																									   .cancelReason(request.getReason())
																									   .cancelAmount(request.getCancelAmount())
																									   .build());
		TossExampleDto.OrderDetail canceled = order.toBuilder()
												   .status(payment.getBalanceAmount() != null && payment.getBalanceAmount() > 0
													   ? TossExampleDto.OrderStatus.PARTIAL_CANCELED : TossExampleDto.OrderStatus.CANCELED)
												   .balanceAmount(payment.getBalanceAmount())
												   .build();
		orders.put(order.getOrderId(), canceled);
		return canceled;
	}

	public TossExampleDto.OrderDetail detail(String orderId) {
		return find(orderId);
	}

	/** 승인 실패 처리 — 승인이 성립했는데 값이 다르면 즉시 취소, 결과불명은 관리자 확인으로 */
	private void handleConfirmFailure(TossExampleDto.OrderDetail order, String paymentKey, CoreException e) {
		if (e.getErrorCode() == TossPayError.AMOUNT_MISMATCH || e.getErrorCode() == TossPayError.ORDER_MISMATCH) {
			try {
				TossPayUtils.cancel(paymentKey, TossPayDto.CancelRequest.builder().cancelReason("주문 정보 불일치 자동취소").build());
				fail(order, "주문 정보가 달라 결제를 취소했습니다.");
			} catch (CoreException cancelError) {
				log.error("토스 불일치 자동취소 실패 - 관리자 확인 필요. orderId={}", order.getOrderId());
				orders.put(order.getOrderId(), order.toBuilder().status(TossExampleDto.OrderStatus.UNKNOWN).paymentKey(paymentKey)
													.failMessage("자동취소 실패 - 관리자 확인 필요").build());
			}
		} else if (e.getErrorCode() == TossPayError.RESULT_UNKNOWN) {
			orders.put(order.getOrderId(), order.toBuilder().status(TossExampleDto.OrderStatus.UNKNOWN).paymentKey(paymentKey)
												.failMessage(e.getMessage()).build());
		} else {
			fail(order, e.getMessage());
		}
	}

	private void fail(TossExampleDto.OrderDetail order, String message) {
		orders.put(order.getOrderId(), order.toBuilder().status(TossExampleDto.OrderStatus.FAILED).failMessage(message).build());
	}

	private TossExampleDto.OrderDetail find(String orderId) {
		TossExampleDto.OrderDetail order = orderId == null ? null : orders.get(orderId);
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}
		return order;
	}
}
