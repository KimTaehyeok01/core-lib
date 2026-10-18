// KSPAY 연동 전용 에러코드 (벤더 결과코드는 KSPAY_{code} 로 패스스루)
package com.corelib.payment.kspay;

import com.corelib.common.error.ErrorCode;

public enum KsPayError implements ErrorCode {

	MID_MISMATCH("KSPAY-MID", "다른 상점아이디로 승인된 결제입니다.", 400),
	ORDER_MISMATCH("KSPAY-ORDER", "주문번호가 기대값과 다릅니다. 취소가 필요합니다.", 400),
	AMOUNT_MISMATCH("KSPAY-AMOUNT", "결제 금액이 기대값과 다릅니다. 취소가 필요합니다.", 400),
	EXTERNAL_ERROR("KSPAY-EXTERNAL", "KSPAY 오류 응답입니다.", 502),
	RESULT_UNKNOWN("KSPAY-UNKNOWN", "결제 결과를 확인할 수 없습니다.", 502);

	private final String code;
	private final String message;
	private final int status;

	KsPayError(String code, String message, int status) {
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
