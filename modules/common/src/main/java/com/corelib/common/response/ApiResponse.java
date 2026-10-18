// 컨트롤러 응답을 통일하는 공통 포맷 {success, data, code, message}
package com.corelib.common.response;

public class ApiResponse<T> {

	/** 요청 성공 여부 */
	private final boolean isSuccess;

	/** 성공 시 실제 데이터 (실패면 null) */
	private final T data;

	/** 실패 시 에러 코드 (성공이면 null) */
	private final String code;

	/** 실패 시 에러 메시지 (성공이면 null) */
	private final String message;

	private ApiResponse(boolean isSuccess, T data, String code, String message) {
		this.isSuccess = isSuccess;
		this.data = data;
		this.code = code;
		this.message = message;
	}

	/** 데이터를 담은 성공 응답 */
	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data, null, null);
	}

	/** 데이터 없는 성공 응답 (삭제 등) */
	public static ApiResponse<Void> success() {
		return new ApiResponse<>(true, null, null, null);
	}

	/** 실패 응답 */
	public static <T> ApiResponse<T> error(String code, String message) {
		return new ApiResponse<>(false, null, code, message);
	}

	public boolean isSuccess() {
		return isSuccess;
	}

	public T getData() {
		return data;
	}

	public String getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}
}
