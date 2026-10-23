package com.corelib.example.payment.kspay.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.payment.kspay.dto.KsPayExampleDto;
import com.corelib.lang.RandomStringUtils;
import com.corelib.lang.StringUtils;
import com.corelib.payment.kspay.KsPayDto;
import com.corelib.payment.kspay.KsPayError;
import com.corelib.payment.kspay.KsPayUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * KSPAY 결제 예제 서비스.
 * <ol>
 *   <li>결제창이 끝나면 화면이 결제키(reCommConId)와 주문번호를 보낸다</li>
 *   <li>{@link KsPayUtils#approve} 를 <b>서버 보관 금액</b>으로 호출 — 결과불명이면 모듈이 같은 결제키로 1회 재전송</li>
 *   <li>승인됐는데 상점아이디·주문번호·금액이 다르면 UNKNOWN — KSTA 에서 거래 확인 후 취소</li>
 *   <li>취소는 승인 때 저장한 결제수단·TID 로 {@link KsPayUtils#cancel} — 결과불명이어도 재시도하지 않는다</li>
 * </ol>
 */
@Slf4j
@Service
public class KsPayExampleService {

	private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final int MAX_PARTIAL_CANCEL = 9;

	/** 실서비스는 주문 테이블 — 예제라 메모리에 둔다 */
	private final Map<String, KsPayExampleDto.OrderDetail> orders = new ConcurrentHashMap<>();
	private final String storeId;

	public KsPayExampleService(@Value("${payment.kspay.mid:}") String storeId) {
		this.storeId = storeId;
	}

	/** 주문 생성 — 주문번호는 PC 50byte 이내, ` ~ ' " 금지 */
	public KsPayExampleDto.OrderResponse createOrder(KsPayExampleDto.OrderRequest request) {
		ValidateUtils.validate(request);
		String orderNo = "KS" + LocalDateTime.now().format(ORDER_NO_TIME) + RandomStringUtils.randomAlphanumeric(6);
		orders.put(orderNo, KsPayExampleDto.OrderDetail.builder()
													   .orderNo(orderNo)
													   .goodsName(request.getGoodsName())
													   .amount(request.getAmount())
													   .status(KsPayExampleDto.OrderStatus.READY)
													   .partialCancelCount(0)
													   .build());
		return KsPayExampleDto.OrderResponse.builder()
											.orderNo(orderNo)
											.goodsName(request.getGoodsName())
											.amount(request.getAmount())
											.storeId(storeId)
											.build();
	}

	/**
	 * 결제창 완료 후 승인.
	 * @param payKey 결제창이 sndReply 페이지로 넘긴 reCommConId
	 */
	public KsPayExampleDto.OrderDetail approve(String orderNo, String payKey) {
		KsPayExampleDto.OrderDetail order = find(orderNo);
		if (order.getStatus() != KsPayExampleDto.OrderStatus.READY) {
			return order;    // 새로고침 등으로 다시 와도 두 번 승인하지 않는다
		}
		if (StringUtils.isBlank(payKey)) {
			fail(order, "결제키가 없습니다. 결제를 다시 진행해 주세요.");
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "결제키가 없습니다.");
		}

		KsPayDto.Approval approval;
		try {
			approval = KsPayUtils.approve(KsPayDto.ApproveRequest.builder()
																 .payKey(payKey)
																 .orderNo(order.getOrderNo())
																 .amount(order.getAmount())
																 .channel(KsPayDto.Channel.PC)    // 모바일 결제창이면 MOBILE
																 .build());
		} catch (CoreException e) {
			handleApproveFailure(order, e);
			throw e;
		}

		KsPayExampleDto.OrderDetail paid = order.toBuilder()
												.status(approval.getPayMethod() == KsPayDto.PayMethod.VACCOUNT
													? KsPayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT : KsPayExampleDto.OrderStatus.PAID)
												.tid(approval.getTid())
												.payMethod(approval.getPayMethod())
												.approvalNo(approval.getApprovalNo())
												.tradedAt(approval.getTradedAt())
												.balanceAmount(order.getAmount())
												.build();
		orders.put(orderNo, paid);
		return paid;
	}

	/** 결제창에서 사용자가 취소 — 주문만 실패로 둔다 */
	public void markCanceledByUser(String orderNo) {
		KsPayExampleDto.OrderDetail order = orderNo == null ? null : orders.get(orderNo);
		if (order != null && order.getStatus() == KsPayExampleDto.OrderStatus.READY) {
			fail(order, "사용자가 결제를 취소했습니다.");
		}
	}

	/** 전액/부분 취소 — 가상계좌는 미입금 회수(recall) */
	public KsPayExampleDto.OrderDetail cancel(KsPayExampleDto.CancelRequest request) {
		ValidateUtils.validate(request);
		KsPayExampleDto.OrderDetail order = find(request.getOrderNo());
		if (order.getTid() == null || order.getPayMethod() == null
				|| (order.getStatus() != KsPayExampleDto.OrderStatus.PAID && order.getStatus() != KsPayExampleDto.OrderStatus.PARTIAL_CANCELED
					&& order.getStatus() != KsPayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT)) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소할 수 없는 주문 상태입니다.");
		}

		boolean isPartial = request.getCancelAmount() != null && request.getCancelAmount() < order.getBalanceAmount();
		if (request.getCancelAmount() != null && request.getCancelAmount() > order.getBalanceAmount()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소 금액이 취소 가능 잔액보다 큽니다.");
		}
		if (isPartial && order.getPartialCancelCount() >= MAX_PARTIAL_CANCEL) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "부분취소는 최대 9회까지 가능합니다.");
		}
		// 이미 부분취소한 건의 잔액 전부를 취소할 때도 부분취소 API 를 쓴다 — 전액취소는 원거래 전체를 되돌리는 요청
		boolean isPartialRequest = isPartial || order.getPartialCancelCount() > 0;
		int cancelAmount = request.getCancelAmount() == null ? order.getBalanceAmount() : request.getCancelAmount();

		KsPayDto.CancelRequest.CancelRequestBuilder cancel = KsPayDto.CancelRequest.builder()
																				   .payMethod(order.getPayMethod())
																				   .tradeKeyType(KsPayDto.TradeKeyType.TID)
																				   .tradeKey(order.getTid());
		if (isPartialRequest) {
			cancel.cancelType(KsPayDto.CancelType.PARTIAL).cancelAmount(cancelAmount).cancelSeq(order.getPartialCancelCount() + 1);
		}
		KsPayUtils.cancel(cancel.build());

		int balance = order.getBalanceAmount() - cancelAmount;
		KsPayExampleDto.OrderDetail canceled = order.toBuilder()
													.status(balance > 0 ? KsPayExampleDto.OrderStatus.PARTIAL_CANCELED : KsPayExampleDto.OrderStatus.CANCELED)
													.balanceAmount(balance)
													.partialCancelCount(isPartialRequest ? order.getPartialCancelCount() + 1 : order.getPartialCancelCount())
													.build();
		orders.put(order.getOrderNo(), canceled);
		return canceled;
	}

	public KsPayExampleDto.OrderDetail detail(String orderNo) {
		return find(orderNo);
	}

	/**
	 * 승인 실패 처리.
	 * <ul>
	 *   <li>상점아이디·주문번호·금액 불일치 — 승인은 성립했지만 예외에 TID 가 없어 자동취소할 수 없다 → 관리자 확인</li>
	 *   <li>RESULT_UNKNOWN — 재전송으로도 확인 불가, KSTA 관리자 확인</li>
	 *   <li>그 외(KSPAY_{코드} 거절, 결제키 무효) — 결제 미성립</li>
	 * </ul>
	 */
	private void handleApproveFailure(KsPayExampleDto.OrderDetail order, CoreException e) {
		if (e.getErrorCode() == KsPayError.MID_MISMATCH || e.getErrorCode() == KsPayError.ORDER_MISMATCH
				|| e.getErrorCode() == KsPayError.AMOUNT_MISMATCH || e.getErrorCode() == KsPayError.RESULT_UNKNOWN) {
			log.error("KSPAY 승인 확인 필요 - KSTA 에서 거래 확인 후 취소. orderNo={}, code={}", order.getOrderNo(), e.getErrorCode().code());
			orders.put(order.getOrderNo(), order.toBuilder().status(KsPayExampleDto.OrderStatus.UNKNOWN).failMessage(e.getMessage()).build());
		} else {
			fail(order, e.getMessage());
		}
	}

	private void fail(KsPayExampleDto.OrderDetail order, String message) {
		orders.put(order.getOrderNo(), order.toBuilder().status(KsPayExampleDto.OrderStatus.FAILED).failMessage(message).build());
	}

	private KsPayExampleDto.OrderDetail find(String orderNo) {
		KsPayExampleDto.OrderDetail order = orderNo == null ? null : orders.get(orderNo);
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}
		return order;
	}
}
