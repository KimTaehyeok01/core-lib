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
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * KSPAY 결제 예제 서비스.
 * <ol>
 *   <li>결제창이 끝나면 화면이 결제키(reCommConId)와 주문번호를 보낸다</li>
 *   <li>READY → PROCESSING 을 한 요청만 선점한 뒤 {@link KsPayUtils#approve} 를 <b>서버 보관 금액</b>으로 호출 — 결과불명이면 모듈이 같은 결제키로 1회 재전송</li>
 *   <li>승인됐는데 상점아이디·주문번호·금액이 다르거나 결제수단을 모르면 UNKNOWN — KSTA 에서 거래 확인 후 취소</li>
 *   <li>취소는 CANCELING 을 선점하고 승인 때 저장한 결제수단·TID 로 {@link KsPayUtils#cancel} — 결과불명이면 CANCEL_UNKNOWN 으로 남기고 재시도하지 않는다</li>
 * </ol>
 */
@Slf4j
@Service
public class KsPayExampleService {

	private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final int MAX_PARTIAL_CANCEL = 9;
	private static final Set<KsPayExampleDto.OrderStatus> CANCELABLE = EnumSet.of(KsPayExampleDto.OrderStatus.PAID,
		KsPayExampleDto.OrderStatus.PARTIAL_CANCELED, KsPayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT);

	/** 실서비스는 주문 테이블 — 예제라 메모리에 둔다. 상태 전이는 compute 로 원자 처리 — 실서비스는 DB 조건부 UPDATE(WHERE status = ?) 로 같은 원자성 확보 */
	private final ConcurrentHashMap<String, KsPayExampleDto.OrderDetail> orders = new ConcurrentHashMap<>();
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
	 * @return 승인한 주문. 이미 처리 중이거나 처리된 주문이면 승인하지 않고 현재 주문
	 */
	public KsPayExampleDto.OrderDetail approve(String orderNo, String payKey) {
		KsPayExampleDto.OrderDetail order = find(orderNo);
		if (order.getStatus() != KsPayExampleDto.OrderStatus.READY) {
			return order;    // 새로고침 등으로 다시 와도 두 번 승인하지 않는다
		}
		if (StringUtils.isBlank(payKey)) {
			// 브라우저 입력이라 실패로 확정하지 않는다 — READY 로 두어 다시 결제할 수 있게
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "결제키가 없습니다. 결제를 다시 진행해 주세요.");
		}
		// 한 요청만 선점한다 — 진 요청이 이미 쓴 결제키로 다시 승인하면 거절 응답으로 PAID 를 덮는다
		KsPayExampleDto.OrderDetail processing = order.toBuilder().status(KsPayExampleDto.OrderStatus.PROCESSING).failMessage(null).build();
		if (!replaceIf(orderNo, KsPayExampleDto.OrderStatus.READY, processing)) {
			return find(orderNo);
		}

		KsPayDto.Approval approval;
		try {
			approval = KsPayUtils.approve(KsPayDto.ApproveRequest.builder()
																 .payKey(payKey)
																 .orderNo(processing.getOrderNo())
																 .amount(processing.getAmount())
																 .channel(KsPayDto.Channel.PC)    // 모바일 결제창이면 MOBILE
																 .build());
		} catch (CoreException e) {
			handleApproveFailure(processing, e);
			throw e;
		}

		KsPayDto.PayMethod payMethod = approval.getPayMethod();
		KsPayExampleDto.OrderDetail.OrderDetailBuilder approved = processing.toBuilder()
																			.tid(approval.getTid())
																			.payMethod(payMethod)
																			.approvalNo(approval.getApprovalNo())
																			.tradedAt(approval.getTradedAt());
		if (payMethod == null) {
			// 승인은 성립했지만 취소 경로(결제수단)를 정할 수 없다 — PAID 로 두면 취소할 수 없는 주문이 된다
			log.error("KSPAY 미지원 결제수단 - KSTA 에서 거래 확인 후 취소. orderNo={}, tid={}, approvalType={}",
				orderNo, approval.getTid(), approval.getApprovalType());
			approved.status(KsPayExampleDto.OrderStatus.UNKNOWN).failMessage("지원하지 않는 결제수단입니다. 관리자 확인이 필요합니다.");
		} else {
			approved.status(payMethod == KsPayDto.PayMethod.VACCOUNT ? KsPayExampleDto.OrderStatus.WAITING_FOR_DEPOSIT : KsPayExampleDto.OrderStatus.PAID)
					.balanceAmount(processing.getAmount());
		}
		KsPayExampleDto.OrderDetail result = approved.build();
		replaceIf(orderNo, KsPayExampleDto.OrderStatus.PROCESSING, result);
		return result;
	}

	/** 결제창에서 사용자가 취소 — 서명 없는 브라우저 신호라 실패로 확정하지 않는다 (READY 유지, 사유만 서버 고정 문구로) */
	public void markCanceledByUser(String orderNo) {
		KsPayExampleDto.OrderDetail order = orderNo == null ? null : orders.get(orderNo);
		if (order != null && order.getStatus() == KsPayExampleDto.OrderStatus.READY) {
			replaceIf(orderNo, KsPayExampleDto.OrderStatus.READY, order.toBuilder().failMessage("사용자가 결제를 취소했습니다.").build());
		}
	}

	/** 전액/부분 취소 — 가상계좌는 미입금 회수(recall) */
	public KsPayExampleDto.OrderDetail cancel(KsPayExampleDto.CancelRequest request) {
		ValidateUtils.validate(request);
		// CANCELING 을 한 요청만 선점한다 — 잔액·회차는 선점 순간의 최신 주문으로 계산한다 (먼저 읽은 값으로 계산하면 동시 부분취소가 같은 회차를 쓴다)
		KsPayExampleDto.OrderDetail[] claimed = new KsPayExampleDto.OrderDetail[1];
		orders.computeIfPresent(request.getOrderNo(), (orderNo, current) -> {
			validateCancelable(current, request.getCancelAmount());
			claimed[0] = current;
			return current.toBuilder().status(KsPayExampleDto.OrderStatus.CANCELING).build();
		});
		KsPayExampleDto.OrderDetail order = claimed[0];
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}

		boolean isPartial = request.getCancelAmount() != null && request.getCancelAmount() < order.getBalanceAmount();
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
		try {
			KsPayUtils.cancel(cancel.build());
		} catch (CoreException e) {
			handleCancelFailure(order, e);
			throw e;
		}

		int balance = order.getBalanceAmount() - cancelAmount;
		KsPayExampleDto.OrderDetail canceled = order.toBuilder()
													.status(balance > 0 ? KsPayExampleDto.OrderStatus.PARTIAL_CANCELED : KsPayExampleDto.OrderStatus.CANCELED)
													.balanceAmount(balance)
													.partialCancelCount(isPartialRequest ? order.getPartialCancelCount() + 1 : order.getPartialCancelCount())
													.build();
		replaceIf(order.getOrderNo(), KsPayExampleDto.OrderStatus.CANCELING, canceled);
		return canceled;
	}

	public KsPayExampleDto.OrderDetail detail(String orderNo) {
		return find(orderNo);
	}

	/**
	 * 승인 실패 처리 — 선점한 PROCESSING 일 때만 전이한다.
	 * <ul>
	 *   <li>상점아이디·주문번호·금액 불일치 — 승인은 성립했지만 예외에 TID 가 없어 자동취소할 수 없다 → 관리자 확인</li>
	 *   <li>RESULT_UNKNOWN — 재전송으로도 확인 불가, KSTA 관리자 확인</li>
	 *   <li>BAD_REQUEST — 전송 전 검증 실패라 결제 시도 자체가 없다 → READY 로 되돌려 다시 결제 가능</li>
	 *   <li>그 외(KSPAY_{코드} 거절, 결제키 무효) — 결제 미성립</li>
	 * </ul>
	 */
	private void handleApproveFailure(KsPayExampleDto.OrderDetail processing, CoreException e) {
		if (e.getErrorCode() == CommonErrorCode.BAD_REQUEST) {
			replaceIf(processing.getOrderNo(), KsPayExampleDto.OrderStatus.PROCESSING,
				processing.toBuilder().status(KsPayExampleDto.OrderStatus.READY).build());
			return;
		}
		KsPayExampleDto.OrderStatus status = KsPayExampleDto.OrderStatus.FAILED;
		if (e.getErrorCode() == KsPayError.MID_MISMATCH || e.getErrorCode() == KsPayError.ORDER_MISMATCH
				|| e.getErrorCode() == KsPayError.AMOUNT_MISMATCH || e.getErrorCode() == KsPayError.RESULT_UNKNOWN) {
			log.error("KSPAY 승인 확인 필요 - KSTA 에서 거래 확인 후 취소. orderNo={}, code={}", processing.getOrderNo(), e.getErrorCode().code());
			status = KsPayExampleDto.OrderStatus.UNKNOWN;
		}
		replaceIf(processing.getOrderNo(), KsPayExampleDto.OrderStatus.PROCESSING,
			processing.toBuilder().status(status).failMessage(e.getMessage()).build());
	}

	/** 취소 실패 처리 — 결과불명은 CANCEL_UNKNOWN 으로 남겨 재취소를 막고(이중취소 위험), 그 외(거절·미전송)는 선점 전 상태로 되돌린다 */
	private void handleCancelFailure(KsPayExampleDto.OrderDetail order, CoreException e) {
		if (e.getErrorCode() == KsPayError.RESULT_UNKNOWN) {
			log.error("KSPAY 취소 결과불명 - 재취소 차단, KSTA 에서 거래 확인 필요. orderNo={}", order.getOrderNo());
			replaceIf(order.getOrderNo(), KsPayExampleDto.OrderStatus.CANCELING,
				order.toBuilder().status(KsPayExampleDto.OrderStatus.CANCEL_UNKNOWN).failMessage(e.getMessage()).build());
		} else {
			replaceIf(order.getOrderNo(), KsPayExampleDto.OrderStatus.CANCELING, order);
		}
	}

	/** 선점 compute 안에서 최신 주문으로 검사한다 — 예외를 던지면 주문은 바뀌지 않는다 */
	private static void validateCancelable(KsPayExampleDto.OrderDetail order, Integer cancelAmount) {
		if (order.getTid() == null || order.getPayMethod() == null || !CANCELABLE.contains(order.getStatus())) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소할 수 없는 주문 상태입니다.");
		}
		if (cancelAmount != null && cancelAmount > order.getBalanceAmount()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "취소 금액이 취소 가능 잔액보다 큽니다.");
		}
		boolean isPartial = cancelAmount != null && cancelAmount < order.getBalanceAmount();
		if (isPartial && order.getPartialCancelCount() >= MAX_PARTIAL_CANCEL) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "부분취소는 최대 9회까지 가능합니다.");
		}
	}

	/** 현재 상태가 expected 일 때만 next 로 바꾼다 — compute 한 번이라 동시 요청 중 하나만 성공한다 */
	private boolean replaceIf(String orderNo, KsPayExampleDto.OrderStatus expected, KsPayExampleDto.OrderDetail next) {
		return orders.computeIfPresent(orderNo, (key, current) -> current.getStatus() == expected ? next : current) == next;
	}

	private KsPayExampleDto.OrderDetail find(String orderNo) {
		KsPayExampleDto.OrderDetail order = orderNo == null ? null : orders.get(orderNo);
		if (order == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "주문을 찾을 수 없습니다.");
		}
		return order;
	}
}
