package com.corelib.banword;

import com.corelib.common.exception.CoreException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code core.banword.*} 설정 바인딩.
 *
 * <p>입력 텍스트 최대 길이만 노출한다. 데이터 공급은 {@link BanWordSource} 로 추상화되므로
 * 테이블/스키마/컬럼 설정은 더 이상 라이브러리 소관이 아니다.
 */
@Getter
@Setter
@ConfigurationProperties("core.banword")
public class BanWordProperty {

	/** 매칭 대상 입력 텍스트 최대 길이 (문자 단위). 기본 10,000. */
	private int maxTextLength = 10_000;

	/**
	 * 설정값 검증. 기동 시 {@link BanWordInitialize} 생성자가 호출한다.
	 * 위반 시 {@link BanWordError#INVALID_MAX_TEXT_LENGTH}.
	 */
	public void validate() {
		if (maxTextLength < 1 || maxTextLength > 1_000_000) {
			throw new CoreException(BanWordError.INVALID_MAX_TEXT_LENGTH,
				"core.banword.max-text-length=" + maxTextLength + " (허용 범위: 1 ~ 1000000)");
		}
	}
}
