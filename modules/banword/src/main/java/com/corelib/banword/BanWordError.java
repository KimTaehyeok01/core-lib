package com.corelib.banword;

import com.corelib.common.error.ErrorCode;

/**
 * banword 모듈 에러 코드.
 */
public enum BanWordError implements ErrorCode {

	SOURCE_NOT_REGISTERED("E910", "BanWordSource 빈이 등록되지 않았습니다. 소비자 앱에서 BanWordSource 구현을 @Component 로 등록하거나, modules/banword 의존성을 제거하십시오.", 500),
	TEXT_TOO_LONG("E911", "입력 텍스트가 허용된 최대 길이를 초과했습니다.", 400),
	EXTRACTOR_NOT_AVAILABLE("E912", "형태소 분기 호출 시 extractor 모듈이 classpath 에 없습니다. modules/extractor 의존성을 추가하십시오.", 500),
	INVALID_MAX_TEXT_LENGTH("E913", "core.banword.max-text-length 값이 허용 범위를 벗어났습니다. 1 ~ 1000000 사이여야 합니다.", 500);

	private final String code;
	private final String message;
	private final int status;

	BanWordError(String code, String message, int status) {
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
