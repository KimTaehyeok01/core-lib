// ValidateUtils 어노테이션 검증 결과와 에러코드 검증
package com.corelib.common.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidateUtilsTest {

	record SampleRequest(@NotBlank String orderNo, @Positive Integer amount) {
	}

	@Test
	@DisplayName("어노테이션을 모두 지키면 통과")
	void valid() {
		assertThatCode(() -> ValidateUtils.validate(new SampleRequest("order-1", 1000))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("위반이 있으면 BAD_REQUEST - 첫 위반 필드명이 메시지에 포함")
	void invalid() {
		assertThatThrownBy(() -> ValidateUtils.validate(new SampleRequest(" ", -1)))
			.isInstanceOf(CoreException.class)
			.hasMessageStartingWith("amount")
			.extracting(e -> ((CoreException) e).getErrorCode())
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
	}

	@Test
	@DisplayName("대상이 null 이면 BAD_REQUEST")
	void nullTarget() {
		assertThatThrownBy(() -> ValidateUtils.validate(null))
			.isInstanceOf(CoreException.class)
			.extracting(e -> ((CoreException) e).getErrorCode())
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
	}
}
