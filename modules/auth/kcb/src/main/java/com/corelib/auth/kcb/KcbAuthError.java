// KCB 본인확인 전용 에러코드 (벤더 결과코드는 KCB_{RSLT_CD} 로 패스스루)
package com.corelib.auth.kcb;

import com.corelib.common.error.ErrorCode;

public enum KcbAuthError implements ErrorCode {

	MODULE_NOT_FOUND("KCB-MODULE", "본인확인 모듈이 설치되지 않았습니다.", 500),
	LICENSE_NOT_FOUND("KCB-LICENSE", "본인확인 라이선스 파일을 찾을 수 없습니다.", 500),
	RESULT_INCOMPLETE("KCB-RESULT-INCOMPLETE", "본인확인 결과에 필수 항목이 없습니다.", 500),
	EXTERNAL_ERROR("KCB-EXTERNAL", "본인확인 서버와 통신할 수 없습니다.", 502);

	private final String code;
	private final String message;
	private final int status;

	KcbAuthError(String code, String message, int status) {
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
