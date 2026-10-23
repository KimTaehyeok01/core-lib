package com.corelib.common.error;

public enum CommonErrorCode implements ErrorCode {

	BAD_REQUEST("C400", "잘못된 요청입니다.", 400),
	UNAUTHORIZED("C401", "인증이 필요합니다.", 401),
	FORBIDDEN("C403", "권한이 없습니다.", 403),
	NOT_FOUND("C404", "대상을 찾을 수 없습니다.", 404),
	INTERNAL_SERVER("C500", "서버 오류가 발생했습니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	CommonErrorCode(String code, String message, int status) {
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
