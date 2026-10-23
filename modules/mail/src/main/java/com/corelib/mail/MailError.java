package com.corelib.mail;

import com.corelib.common.error.ErrorCode;

public enum MailError implements ErrorCode {

	SEND_FAILED("MAIL-SEND", "메일 발송에 실패했습니다.", 502);

	private final String code;
	private final String message;
	private final int status;

	MailError(String code, String message, int status) {
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
