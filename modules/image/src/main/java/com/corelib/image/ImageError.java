package com.corelib.image;

import com.corelib.common.error.ErrorCode;

/** image 에러코드 */
public enum ImageError implements ErrorCode {

	/** 이미지가 아님·지원하지 않는 형식·손상 */
	INVALID_IMAGE("IMAGE-INVALID", "읽을 수 없는 이미지입니다.", 400),
	/** 가로×세로가 상한(core.image.max-pixels)을 넘음 — 해상도 폭탄 방지 */
	TOO_LARGE("IMAGE-TOO-LARGE", "이미지 해상도가 너무 큽니다.", 400),
	/** 인코딩 실패 */
	PROCESS_FAILED("IMAGE-PROCESS", "이미지 변환에 실패했습니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	ImageError(String code, String message, int status) {
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
