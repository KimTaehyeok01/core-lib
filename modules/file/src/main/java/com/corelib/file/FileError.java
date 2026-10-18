// 파일 업로드/다운로드 전용 에러코드
package com.corelib.file;

import com.corelib.common.error.ErrorCode;

public enum FileError implements ErrorCode {

	INVALID_FILE("FILE-INVALID", "업로드할 수 없는 파일입니다.", 400),
	NOT_FOUND("FILE-NOT-FOUND", "파일을 찾을 수 없습니다.", 404),
	STORAGE_ERROR("FILE-STORAGE", "파일 저장소 오류가 발생했습니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	FileError(String code, String message, int status) {
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
