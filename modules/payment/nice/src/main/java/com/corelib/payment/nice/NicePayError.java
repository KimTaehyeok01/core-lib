// 나이스페이먼츠 연동 전용 에러코드 (벤더 결과코드는 NICEPAY_{resultCode} 로 패스스루)
package com.corelib.payment.nice;

import com.corelib.common.error.ErrorCode;

public enum NicePayError implements ErrorCode {

	INVALID_AUTH_RESULT("NICEPAY-AUTH-INVALID", "결제 인증 결과가 올바르지 않습니다.", 400),
	AUTH_MISMATCH("NICEPAY-AUTH-MISMATCH", "결제 인증 정보가 주문과 다릅니다.", 400),
	SIGNATURE_MISMATCH("NICEPAY-SIGNATURE", "결제 데이터 위변조가 의심됩니다.", 400),
	ORDER_MISMATCH("NICEPAY-ORDER", "주문번호가 기대값과 다릅니다. 취소가 필요합니다.", 400),
	AMOUNT_MISMATCH("NICEPAY-AMOUNT", "결제 금액이 기대값과 다릅니다. 취소가 필요합니다.", 400),
	NET_CANCELLED("NICEPAY-NET-CANCELLED", "결제 응답을 받지 못해 결제를 취소했습니다.", 400),
	EXTERNAL_ERROR("NICEPAY-EXTERNAL", "나이스페이먼츠 오류 응답입니다.", 502),
	RESULT_UNKNOWN("NICEPAY-UNKNOWN", "결제 결과를 확인할 수 없습니다.", 502);

	private final String code;
	private final String message;
	private final int status;

	NicePayError(String code, String message, int status) {
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
