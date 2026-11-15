package com.corelib.extractor;

import com.corelib.common.error.ErrorCode;

public enum ExtractorError implements ErrorCode {

	OKT_LOAD_FAILED("E901", "OKT 사전 로드에 실패했습니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	ExtractorError(String code, String message, int status) {
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
