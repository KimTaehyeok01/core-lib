package com.corelib.common.error;

public interface ErrorCode {

	/** 에러 식별 코드 (예: C400) */
	String code();

	/** 사용자/클라이언트에 전달할 기본 메시지 */
	String message();

	/** HTTP 상태 코드 (Spring 의존을 피하려고 int 로 보관) */
	int status();

	/**
	 * enum 으로 미리 정의할 수 없는 코드를 즉석에서 만든다 — 벤더 결과코드를 그대로 넘길 때 사용.
	 * 예: {@code ErrorCode.of("NICEPAY_" + resultCode, resultMsg, 400)}
	 */
	static ErrorCode of(String code, String message, int status) {
		return new SimpleErrorCode(code, message, status);
	}

	/** {@link #of} 가 돌려주는 단순 구현 - record 접근자가 인터페이스 메서드를 그대로 구현한다 */
	record SimpleErrorCode(String code, String message, int status) implements ErrorCode {
	}
}
