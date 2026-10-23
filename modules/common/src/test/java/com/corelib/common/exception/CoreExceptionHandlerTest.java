package com.corelib.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.response.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class CoreExceptionHandlerTest {

	private final CoreExceptionHandler handler = new CoreExceptionHandler();

	@Test
	@DisplayName("깨진 JSON·파라미터 누락·타입 불일치 - C400")
	void badRequest() {
		List<Exception> errors = List.of(
			new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0])),
			new MissingServletRequestParameterException("orderId", "String"),
			new TypeMismatchException("abc", Integer.class));

		for (Exception error : errors) {
			ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(error);
			assertThat(response.getStatusCode().value()).isEqualTo(400);
			assertThat(response.getBody().getCode()).isEqualTo("C400");
		}
	}

	@Test
	@DisplayName("없는 정적 리소스 - C404, 지원하지 않는 메서드 - C405")
	void notFoundAndMethodNotAllowed() {
		ResponseEntity<ApiResponse<Void>> notFound = handler.handleUnexpected(new NoResourceFoundException(HttpMethod.GET, "/nope.html"));
		assertThat(notFound.getStatusCode().value()).isEqualTo(404);
		assertThat(notFound.getBody().getCode()).isEqualTo("C404");

		ResponseEntity<ApiResponse<Void>> notAllowed = handler.handleUnexpected(new HttpRequestMethodNotSupportedException("PUT"));
		assertThat(notAllowed.getStatusCode().value()).isEqualTo(405);
		assertThat(notAllowed.getBody().getCode()).isEqualTo("C405");
	}

	@Test
	@DisplayName("그 외 예외 - 내부 메시지 숨기고 C500")
	void unexpected() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(new IllegalStateException("db password=secret"));
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().getCode()).isEqualTo("C500");
		assertThat(response.getBody().getMessage()).doesNotContain("secret");
	}
}
