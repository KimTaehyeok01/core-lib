package com.corelib.redis;

import com.corelib.common.error.ErrorCode;

/** redis 에러코드 — 409 는 잠시 뒤 재시도로 풀리고, 500 은 재시도로 풀리지 않는다(인프라 확인) */
public enum RedisError implements ErrorCode {

	/** 같은 키의 다른 요청이 락을 잡고 있어 대기 시간 안에 못 잡음 */
	LOCK_CONFLICT("REDIS-LOCK", "다른 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.", 409),
	/** Redis 연결 실패·타임아웃, 또는 Redis 미설정 */
	UNAVAILABLE("REDIS-UNAVAILABLE", "일시적인 시스템 오류입니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	RedisError(String code, String message, int status) {
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
