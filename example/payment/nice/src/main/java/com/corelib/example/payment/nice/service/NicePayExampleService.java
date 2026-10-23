package com.corelib.example.payment.nice.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.payment.nice.dto.NicePayExampleDto;
import com.corelib.lang.RandomStringUtils;
import com.corelib.payment.nice.NicePayDto;
import com.corelib.payment.nice.NicePayError;
import com.corelib.payment.nice.NicePayUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 나이스페이 결제 예제 서비스.
 * <ol>
 *   <li>returnUrl 인증결과를 {@link NicePayUtils#verifyAuthResult} 로 검증 — 서버 보관 주문번호·금액과 서명 대조</li>
 *   <li>통과한 tid 로 {@link NicePayUtils#approve} — 결과불명이면 모듈이 망취소까지 한다</li>
 *   <li>승인됐는데 응답이 주문과 다르면(위변조·불일치) 즉시 취소</li>
 * </ol>
 */
@Slf4j
@Service
public class NicePayExampleService {

	private static final DateTimeFormatter ORDER_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	/** 실서비스는 주문 테이블 — 예제라 메모리에 둔다 */
	private final Map<String, NicePayExampleDto.OrderDetail> orders = new ConcurrentHashMap<>();
	private final String clientId;

	public NicePayExampleService(@Value("${payment.nice.client-key:}") String clientId) {
		this.clientId = clientId;
	}

	/** 주문 생성 — 금액은 서버가 정해 보관한다 */
	public NicePayExampleDto.OrderResponse createOrder(NicePayExampleDto.OrderRequest request) {
		ValidateUtils.validate(request);
		String orderId = "ORD" + LocalDateTime.now().format(ORDER_ID_TIME) + RandomStringUtils.randomAlphanumeric(8);
		orders.put(orderId, NicePayExampleDto.OrderDetail.builder()
														 .orderId(orderId)
														 .goodsName(request.getGoodsName())
														 .amount(request.getAmount())
														 .status(NicePayExampleDto.OrderStatus.READY)
														 .build());
		return NicePayExampleDto.OrderResponse.builder()
											  .orderId(orderId)
											  .goodsName(request.getGoodsName())
											  .amount(request.getAmount())
											  .clientId(clientId)
											  .build();
	}

	/** returnUrl 콜백 — 인증결과 검증 후 승인 */
	public NicePayExampleDto.OrderDetail approve(NicePayDto.AuthResult authResult) {
		NicePayExampleDto.OrderDetail order = find(authResult.getOrderId());
		if (order.getStatus() != NicePayExampleDto.OrderStatus.READY) {
			return order;    // 새로고침 등으로 콜백이 다시 와도 두 번 승인하지 않는다
		}

		String tid;
		try {
			tid = NicePayUtils.verifyAuthResult(authResult, order.getOrderId(), order.getAmount());
		} catch (CoreException e) {
			fail(order, e.getMessage());    // 인증 실패·사용자 취소·위변조 — 승인 전이라 취소할 것이 없다
			throw e;
		}

		try {
			NicePayDto.Payment payment = NicePayUtils.approve(NicePayDto.ApproveRequest.builder()
																					   .tid(tid)
																					   .orderId(order.getOrderId())
																					   .amount(order.getAmount())
																					   .build());
			NicePayExampleDto.OrderDetail paid = order.toBuilder()
													  .status(payment.isVbankReady() ? NicePayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT
														  : NicePayExampleDto.OrderStatus.PAID)
													  .tid(payment.getTid())
													  .payMethod(payment.getPayMethod())
													  .paidAt(payment.getPaidAt())
													  .balanceAmt(payment.getBalanceAmt())
													  .build();
			orders.put(order.getOrderId(), paid);
			return paid;
		} catch (CoreException e) {
			handleApproveFailure(order, tid, e);
			throw e;
		}
	}

	/** 전액/부분 취소 */
	public NicePayExampleDto.OrderDetail cancel(NicePayExampleDto.CancelRequest request) {
		ValidateUtils.validate(request);
		NicePayExampleDto.OrderDetail order = find(request.getOrderId());
		if (order.getTid() == null
				|| (order.getStatus() != NicePayExampleDto.OrderStatus.PAID && order.getStatus() != NicePayExampleDto.OrderStatus.PARTIAL_CANCELED)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소할 수 없는 주문 상태입니다.");
		}
		NicePayDto.Payment payment = NicePayUtils.cancel(order.getTid(), NicePayDto.CancelRequest.builder()
																								.reason(request.getReason())
																								.orderId(order.getOrderId())
																								.cancelAmt(request.getCancelAmt())
																								.build());
		NicePayExampleDto.OrderDetail canceled = order.toBuilder()
													  .status(payment.getBalanceAmt() != null && payment.getBalanceAmt() > 0
														  ? NicePayExampleDto.OrderStatus.PARTIAL_CANCELED : NicePayExampleDto.OrderStatus.CANCELED)
													  .balanceAmt(payment.getBalanceAmt())
													  .build();
		orders.put(order.getOrderId(), canceled);
		return canceled;
	}

	public NicePayExampleDto.OrderDetail detail(String orderId) {
		return find(orderId);
	}

	/**
	 * 승인 실패 처리.
	 * <ul>
	 *   <li>위변조·주문번호·금액 불일치 — 승인이 성립했을 수 있어 즉시 취소</li>
	 *   <li>NET_CANCELLED — 모듈이 망취소 완료, 결제 미성립 확정</li>
	 *   <li>RESULT_UNKNOWN — 망취소도 실패, 관리자 확인</li>
	 * </ul>
	 */
	private void handleApproveFailure(NicePayExampleDto.OrderDetail order, String tid, CoreException e) {
		if (e.getErrorCode() == NicePayError.SIGNATURE_MISMATCH || e.getErrorCode() == NicePayError.ORDER_MISMATCH
				|| e.getErrorCode() == NicePayError.AMOUNT_MISMATCH) {
			try {
				NicePayUtils.cancel(tid, NicePayDto.CancelRequest.builder().reason("주문 정보 불일치 자동취소").orderId(order.getOrderId()).build());
				fail(order, "주문 정보가 달라 결제를 취소했습니다.");
			} catch (CoreException cancelError) {
				log.error("NICEPAY 불일치 자동취소 실패 - 관리자 확인 필요. orderId={}", order.getOrderId());
				unknown(order, tid, "자동취소 실패 - 관리자 확인 필요");
			}
		} else if (e.getErrorCode() == NicePayError.RESULT_UNKNOWN) {
			unknown(order, tid, e.getMessage());
		} else {
			fail(order, e.getMessage());
		}
	}

	private void unknown(NicePayExampleDto.OrderDetail order, String tid, String message) {
		orders.put(order.getOrderId(), order.toBuilder().status(NicePayExampleDto.OrderStatus.UNKNOWN).tid(tid).failMessage(message).build());
	}

	private void fail(NicePayExampleDto.OrderDetail order, String message) {
		orders.put(order.getOrderId(), order.toBuilder().status(NicePayExampleDto.OrderStatus.FAILED).failMessage(message).build());
	}

	private NicePayExampleDto.OrderDetail find(String orderId) {
		NicePayExampleDto.OrderDetail order = orderId == null ? null : orders.get(orderId);
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}
		return order;
	}
}
