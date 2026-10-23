package com.corelib.excel;

import com.corelib.common.error.ErrorCode;

/** excel 에러코드 */
public enum ExcelError implements ErrorCode {

	/** xlsx·csv 가 아님, 헤더 없음, 필수 헤더 누락, 행 수 초과 */
	INVALID_FILE("EXCEL-INVALID", "읽을 수 없는 파일입니다.", 400),
	/** DTO 정의 오류 (기본 생성자 없음·지원하지 않는 타입) 또는 쓰기 실패 */
	MAPPING_FAILED("EXCEL-MAPPING", "엑셀 변환 설정이 올바르지 않습니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	ExcelError(String code, String message, int status) {
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
