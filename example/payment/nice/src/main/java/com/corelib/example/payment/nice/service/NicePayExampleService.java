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
 *   <li>READY → PROCESSING 을 선점한 콜백만 {@link NicePayUtils#approve} — 결과불명이면 모듈이 망취소까지 한다</li>
 *   <li>승인됐는데 응답이 주문과 다르면(위변조·불일치) 즉시 취소</li>
 * </ol>
 * 외부 호출 전에 상태를 한 번만 전이(선점)하고, 결과는 선점한 중간 상태(PROCESSING·CANCELING)일 때만 저장한다.
 */
@Slf4j
@Service
public class NicePayExampleService {

	private static final DateTimeFormatter ORDER_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	/** 서명 검증 전 브라우저 입력(authResultMsg)은 화면에 그대로 보여주지 않는다 */
	private static final String AUTH_FAILED_MESSAGE = "결제 인증이 완료되지 않았습니다. 다시 시도해 주세요.";

	/**
	 * 실서비스는 주문 테이블 — 예제라 메모리에 둔다.
	 * 실서비스는 DB 조건부 UPDATE(WHERE status = ?) 로 같은 원자성 확보 — 여기서는 replace(key, 읽은 값, 새 값)
	 */
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

	/** returnUrl 콜백 — 인증결과 검증 후 READY → PROCESSING 을 선점한 요청만 승인한다 */
	public NicePayExampleDto.OrderDetail approve(NicePayDto.AuthResult authResult) {
		NicePayExampleDto.OrderDetail order = find(authResult.getOrderId());
		if (order.getStatus() != NicePayExampleDto.OrderStatus.READY) {
			return reentered(order);    // 새로고침 등으로 콜백이 다시 와도 두 번 승인하지 않는다
		}

		String tid;
		try {
			tid = NicePayUtils.verifyAuthResult(authResult, order.getOrderId(), order.getAmount());
		} catch (CoreException e) {
			// 인증 실패·사용자 취소·위변조 — 서명 검증 전 브라우저 입력이라 FAILED 로 확정하지 않는다(READY 유지, 다시 결제 가능).
			// 승인 전이라 취소할 것도 없다.
			throw new CoreException(e.getErrorCode(), AUTH_FAILED_MESSAGE, e);
		}

		NicePayExampleDto.OrderDetail processing = order.toBuilder().status(NicePayExampleDto.OrderStatus.PROCESSING).build();
		if (!orders.replace(order.getOrderId(), order, processing)) {
			// 선점 패자 — 다른 콜백이 승인 중이거나 끝냈다. 승인·망취소를 부르면 성공한 결제를 되돌릴 수 있어 부르지 않는다.
			return reentered(find(order.getOrderId()));
		}

		try {
			NicePayDto.Payment payment = NicePayUtils.approve(NicePayDto.ApproveRequest.builder()
																					   .tid(tid)
																					   .orderId(order.getOrderId())
																					   .amount(order.getAmount())
																					   .build());
			return settle(processing, processing.toBuilder()
												.status(payment.isVbankReady() ? NicePayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT
													: NicePayExampleDto.OrderStatus.PAID)
												.tid(payment.getTid())
												.payMethod(payment.getPayMethod())
												.paidAt(payment.getPaidAt())
												.balanceAmt(payment.getBalanceAmt())
												.build());
		} catch (CoreException e) {
			handleApproveFailure(processing, tid, e);
			throw e;
		}
	}

	/**
	 * 전액/부분 취소 — PAID·PARTIAL_CANCELED → CANCELING 을 선점한 요청만 NICEPAY 를 호출한다.
	 * <ul>
	 *   <li>취소 orderId 는 회차마다 {orderId}-c{회차} — 같은 orderId 로 재호출할 수 없다(매뉴얼)</li>
	 *   <li>결과불명이면 CANCEL_UNKNOWN 으로 남겨 재취소(이중 환불)를 막는다. 거절·미전송이면 원래 상태로 되돌린다</li>
	 * </ul>
	 */
	public NicePayExampleDto.OrderDetail cancel(NicePayExampleDto.CancelRequest request) {
		ValidateUtils.validate(request);
		NicePayExampleDto.OrderDetail order = find(request.getOrderId());
		if (order.getTid() == null
				|| (order.getStatus() != NicePayExampleDto.OrderStatus.PAID && order.getStatus() != NicePayExampleDto.OrderStatus.PARTIAL_CANCELED)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소할 수 없는 주문 상태입니다.");
		}
		// 회차는 선점과 함께 올리고 실패해도 되돌리지 않는다 — 한 번 보낸 취소 orderId 를 다시 쓰지 않는다
		NicePayExampleDto.OrderDetail canceling = order.toBuilder()
													   .status(NicePayExampleDto.OrderStatus.CANCELING)
													   .cancelCount(order.getCancelCount() + 1)
													   .build();
		if (!orders.replace(order.getOrderId(), order, canceling)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "다른 요청이 처리 중인 주문입니다. 잠시 후 다시 확인해 주세요.");
		}

		NicePayDto.Payment payment;
		try {
			payment = NicePayUtils.cancel(canceling.getTid(), NicePayDto.CancelRequest.builder()
																					  .reason(request.getReason())
																					  .orderId(canceling.getOrderId() + "-c" + canceling.getCancelCount())
																					  .cancelAmt(request.getCancelAmt())
																					  .build());
		} catch (CoreException e) {
			if (e.getErrorCode() == NicePayError.RESULT_UNKNOWN) {
				log.error("NICEPAY 취소 결과불명 - 관리자 확인 필요. orderId={}, cancelCount={}", canceling.getOrderId(), canceling.getCancelCount());
				settle(canceling, canceling.toBuilder().status(NicePayExampleDto.OrderStatus.CANCEL_UNKNOWN).failMessage(e.getMessage()).build());
			} else {
				settle(canceling, canceling.toBuilder().status(order.getStatus()).build());
			}
			throw e;
		}
		return settle(canceling, canceling.toBuilder()
										  .status(payment.getBalanceAmt() != null && payment.getBalanceAmt() > 0
											  ? NicePayExampleDto.OrderStatus.PARTIAL_CANCELED : NicePayExampleDto.OrderStatus.CANCELED)
										  .balanceAmt(payment.getBalanceAmt())
										  .build());
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
	private void handleApproveFailure(NicePayExampleDto.OrderDetail processing, String tid, CoreException e) {
		if (e.getErrorCode() == NicePayError.SIGNATURE_MISMATCH || e.getErrorCode() == NicePayError.ORDER_MISMATCH
				|| e.getErrorCode() == NicePayError.AMOUNT_MISMATCH) {
			try {
				NicePayUtils.cancel(tid, NicePayDto.CancelRequest.builder().reason("주문 정보 불일치 자동취소").orderId(processing.getOrderId()).build());
				fail(processing, "주문 정보가 달라 결제를 취소했습니다.");
			} catch (CoreException cancelError) {
				log.error("NICEPAY 불일치 자동취소 실패 - 관리자 확인 필요. orderId={}", processing.getOrderId());
				unknown(processing, tid, "자동취소 실패 - 관리자 확인 필요");
			}
		} else if (e.getErrorCode() == NicePayError.RESULT_UNKNOWN) {
			unknown(processing, tid, e.getMessage());
		} else {
			fail(processing, e.getMessage());
		}
	}

	/**
	 * 재진입·동시 콜백 — 결제 미성립이 확정된 FAILED 만 실패 화면으로, 결제가 진행·성립된 그 외 상태(승인 중·완료·취소·결과불명)는
	 * 결과 화면으로 보낸다(result.html 이 현재 상태를 보여 주고, 새로고침하면 확정 상태가 보인다).
	 */
	private NicePayExampleDto.OrderDetail reentered(NicePayExampleDto.OrderDetail order) {
		if (order.getStatus() == NicePayExampleDto.OrderStatus.FAILED) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "이미 실패 처리된 주문입니다. 다시 주문해 주세요.");
		}
		return order;
	}

	private void unknown(NicePayExampleDto.OrderDetail processing, String tid, String message) {
		settle(processing, processing.toBuilder().status(NicePayExampleDto.OrderStatus.UNKNOWN).tid(tid).failMessage(message).build());
	}

	private void fail(NicePayExampleDto.OrderDetail processing, String message) {
		settle(processing, processing.toBuilder().status(NicePayExampleDto.OrderStatus.FAILED).failMessage(message).build());
	}

	/** 선점한 중간 상태(PROCESSING·CANCELING)일 때만 확정한다 — 처음 읽은 스냅샷으로 덮어쓰지 않는다 */
	private NicePayExampleDto.OrderDetail settle(NicePayExampleDto.OrderDetail claimed, NicePayExampleDto.OrderDetail next) {
		return orders.replace(claimed.getOrderId(), claimed, next) ? next : find(claimed.getOrderId());
	}

	private NicePayExampleDto.OrderDetail find(String orderId) {
		NicePayExampleDto.OrderDetail order = orderId == null ? null : orders.get(orderId);
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}
		return order;
	}
}
