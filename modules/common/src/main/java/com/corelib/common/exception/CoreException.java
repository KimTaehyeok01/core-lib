// ErrorCode 를 담아 던지는 core 공통 런타임 예외 (로그용 식별 데이터를 함께 실을 수 있음)
package com.corelib.common.exception;

import com.corelib.common.error.ErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class CoreException extends RuntimeException {

	private final ErrorCode errorCode;

	/** 서버 로그에만 남길 식별 데이터 (주문번호, tid 등) - 응답 본문에는 나가지 않는다 */
	private final Map<String, Object> errorLogData = new LinkedHashMap<>();

	/** 에러코드만으로 던질 때 - 메시지는 errorCode 의 기본 메시지를 사용 */
	public CoreException(ErrorCode errorCode) {
		super(errorCode.message());
		this.errorCode = errorCode;
	}

	/** 상황별 상세 메시지를 덧붙일 때 */
	public CoreException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	/** 원인 예외(cause)를 함께 보존할 때 */
	public CoreException(ErrorCode errorCode, String detail, Throwable cause) {
		super(detail, cause);
		this.errorCode = errorCode;
	}

	/**
	 * 로그용 식별 데이터를 덧붙인다 — 체이닝용으로 자신을 돌려준다.
	 * 개인정보(이름·휴대폰·카드번호·이메일)는 넣지 않는다.
	 */
	public CoreException addErrorLogData(String key, Object value) {
		this.errorLogData.put(key, value);
		return this;
	}

	/** 여러 식별 데이터를 한 번에 덧붙인다 */
	public CoreException addErrorLogData(Map<String, ?> data) {
		if (data != null) {
			this.errorLogData.putAll(data);
		}
		return this;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Map<String, Object> getErrorLogData() {
		return Collections.unmodifiableMap(errorLogData);
	}
}
