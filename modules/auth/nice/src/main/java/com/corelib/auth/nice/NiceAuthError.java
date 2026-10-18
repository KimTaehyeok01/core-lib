// NICE 통합인증 전용 에러코드 (벤더 결과코드는 NICE_{result_code} 로 패스스루)
package com.corelib.auth.nice;

import com.corelib.common.error.ErrorCode;

public enum NiceAuthError implements ErrorCode {

	TX_INVALID("NICE-TX-INVALID", "유효하지 않은 인증 거래입니다.", 400),
	TX_EXPIRED("NICE-TX-EXPIRED", "인증 유효시간이 만료되었습니다. 다시 인증해 주세요.", 400),
	INTEGRITY_FAILED("NICE-INTEGRITY", "인증 결과 무결성 검증에 실패했습니다.", 401),
	DECRYPT_FAILED("NICE-DECRYPT", "인증 결과를 해석할 수 없습니다.", 500),
	RESULT_INCOMPLETE("NICE-RESULT-INCOMPLETE", "인증 결과에 필수 항목이 없습니다.", 500),
	EXTERNAL_ERROR("NICE-EXTERNAL", "본인인증 서버와 통신할 수 없습니다.", 502);

	private final String code;
	private final String message;
	private final int status;

	NiceAuthError(String code, String message, int status) {
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
