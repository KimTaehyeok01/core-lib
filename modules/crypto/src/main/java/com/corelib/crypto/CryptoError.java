package com.corelib.crypto;

import com.corelib.common.error.ErrorCode;

public enum CryptoError implements ErrorCode {

	INVALID_KEY("CRYPTO-KEY", "암호화 키가 올바르지 않습니다.", 500),
	INVALID_ENCODING("CRYPTO-ENCODING", "인코딩된 값의 형식이 올바르지 않습니다.", 400),
	ENCRYPT_FAILED("CRYPTO-ENCRYPT", "암호화에 실패했습니다.", 500),
	DECRYPT_FAILED("CRYPTO-DECRYPT", "복호화할 수 없는 데이터입니다.", 400);

	private final String code;
	private final String message;
	private final int status;

	CryptoError(String code, String message, int status) {
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
