package com.corelib.payment.kspay;

import com.corelib.common.exception.CoreException;
import lombok.Getter;

/**
 * 승인은 성립했는데 응답이 주문과 다르다 (KSPAY-MID·ORDER·AMOUNT) — 취소에 쓸 tid·payMethod 를 담는다.
 * <p>주문번호·금액 불일치는 이 값으로 바로 자동취소한다. 상점아이디 불일치는 다른 상점의 승인이라 우리 api-key 로 취소되지 않는다
 * — {@link #isCancelable()} 가 false 면 KSTA 에서 확인한다.
 * <pre>{@code
 * try {
 *     approval = KsPayUtils.approve(request);
 * } catch (KsPayMismatchException e) {
 *     if (e.isCancelable()) {
 *         KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
 *             .payMethod(e.getPayMethod()).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey(e.getTid()).build());
 *     }
 *     throw e;
 * }
 * }</pre>
 */
@Getter
public class KsPayMismatchException extends CoreException {

	/** 승인 응답의 거래번호 (trno) — 취소 tradeKey */
	private final String tid;

	/** 승인구분으로 판별한 결제수단 — 모르는 값이면 null (취소 경로를 정할 수 없다) */
	private final KsPayDto.PayMethod payMethod;

	KsPayMismatchException(KsPayError errorCode, String detail, String tid, KsPayDto.PayMethod payMethod) {
		super(errorCode, detail);
		this.tid = tid;
		this.payMethod = payMethod;
		addErrorLogData("tid", tid);
		addErrorLogData("payMethod", payMethod);
	}

	/** @return 우리 상점 승인이고 tid·결제수단을 알아 {@link KsPayUtils#cancel} 로 취소할 수 있으면 true */
	public boolean isCancelable() {
		return getErrorCode() != KsPayError.MID_MISMATCH && tid != null && payMethod != null;
	}
}
