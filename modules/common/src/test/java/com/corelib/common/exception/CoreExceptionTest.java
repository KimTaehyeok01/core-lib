package com.corelib.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoreExceptionTest {

	@Test
	@DisplayName("addErrorLogData 는 체이닝되고 순서를 유지한다")
	void errorLogData() {
		CoreException e = new CoreException(CommonErrorCode.BAD_REQUEST, "잘못된 요청")
			.addErrorLogData("orderId", "order-1")
			.addErrorLogData(Map.of("tid", "tid-1"));

		assertThat(e.getErrorLogData()).containsExactly(Map.entry("orderId", "order-1"), Map.entry("tid", "tid-1"));
		assertThatThrownBy(() -> e.getErrorLogData().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("ErrorCode.of 로 벤더 결과코드를 그대로 담는다")
	void adHocErrorCode() {
		CoreException e = new CoreException(ErrorCode.of("NICEPAY_3011", "카드 한도 초과", 400));

		assertThat(e.getErrorCode().code()).isEqualTo("NICEPAY_3011");
		assertThat(e.getErrorCode().status()).isEqualTo(400);
		assertThat(e.getMessage()).isEqualTo("카드 한도 초과");
	}
}
