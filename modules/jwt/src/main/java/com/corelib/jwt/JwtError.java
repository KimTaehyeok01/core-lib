package com.corelib.jwt;

import com.corelib.common.error.ErrorCode;

/** jwt 에러코드 — 401 은 클라이언트가 코드로 "갱신할지 / 다시 로그인할지" 판단한다 */
public enum JwtError implements ErrorCode {

	/** 만료 — 액세스 토큰이면 리프레시 토큰으로 갱신 */
	EXPIRED("JWT-EXPIRED", "토큰이 만료되었습니다.", 401),
	/** 서명·형식·발급자·알고리즘 불일치 — 다시 로그인 */
	INVALID("JWT-INVALID", "유효하지 않은 토큰입니다.", 401),
	/** 액세스 자리에 리프레시(또는 반대)를 넣음 */
	WRONG_TYPE("JWT-TYPE", "토큰 종류가 올바르지 않습니다.", 401),
	/** 검증 전용(공개키만) 설정에서 발급 시도 */
	NOT_ISSUABLE("JWT-KEY", "토큰을 발급할 수 없는 설정입니다. 개인키가 필요합니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	JwtError(String code, String message, int status) {
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
