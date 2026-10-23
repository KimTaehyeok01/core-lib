package com.corelib.payment.toss;

import com.corelib.common.error.ErrorCode;

public enum TossPayError implements ErrorCode {

	ORDER_MISMATCH("TOSS-ORDER", "주문번호가 기대값과 다릅니다.", 400),
	AMOUNT_MISMATCH("TOSS-AMOUNT", "결제 금액이 기대값과 다릅니다. 취소가 필요합니다.", 400),
	EXTERNAL_ERROR("TOSS-EXTERNAL", "토스페이먼츠 오류 응답입니다.", 502),
	NOT_APPROVED("TOSS-NOT-APPROVED", "결제가 승인되지 않았습니다.", 400),
	RESULT_UNKNOWN("TOSS-UNKNOWN", "결제 결과를 확인할 수 없습니다.", 502);

	private final String code;
	private final String message;
	private final int status;

	TossPayError(String code, String message, int status) {
		this.code = code;
		this.message = message;
		this.status = status;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public String message() {
		return message;
	}

	@Override
	public int status() {
		return status;
	}
}
