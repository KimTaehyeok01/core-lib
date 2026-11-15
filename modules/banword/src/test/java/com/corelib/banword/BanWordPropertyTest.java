package com.corelib.banword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BanWordPropertyTest {

	@Test
	@DisplayName("기본값 maxTextLength=10000 은 validate 통과")
	void defaultValuesPass() {
		BanWordProperty property = new BanWordProperty();

		assertThatCode(property::validate).doesNotThrowAnyException();
		assertThat(property.getMaxTextLength()).isEqualTo(10_000);
	}

	@Test
	@DisplayName("maxTextLength=0 은 INVALID_MAX_TEXT_LENGTH")
	void rejectZero() {
		BanWordProperty property = new BanWordProperty();
		property.setMaxTextLength(0);

		assertThatThrownBy(property::validate)
			.isInstanceOf(CoreException.class)
			.satisfies(ex -> assertThat(((CoreException) ex).getErrorCode())
				.isEqualTo(BanWordError.INVALID_MAX_TEXT_LENGTH));
	}

	@Test
	@DisplayName("maxTextLength 가 1,000,001 이면 거부")
	void rejectAboveMax() {
		BanWordProperty property = new BanWordProperty();
		property.setMaxTextLength(1_000_001);

		assertThatThrownBy(property::validate).isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("음수는 거부")
	void rejectNegative() {
		BanWordProperty property = new BanWordProperty();
		property.setMaxTextLength(-1);

		assertThatThrownBy(property::validate).isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("경계값 1 과 1,000,000 은 통과")
	void acceptBoundary() {
		BanWordProperty low = new BanWordProperty();
		low.setMaxTextLength(1);
		assertThatCode(low::validate).doesNotThrowAnyException();

		BanWordProperty high = new BanWordProperty();
		high.setMaxTextLength(1_000_000);
		assertThatCode(high::validate).doesNotThrowAnyException();
	}
}
