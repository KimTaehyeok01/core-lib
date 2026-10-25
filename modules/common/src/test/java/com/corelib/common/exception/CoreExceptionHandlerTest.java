package com.corelib.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.response.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class CoreExceptionHandlerTest {

	private final CoreExceptionHandler handler = new CoreExceptionHandler();
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void attachLog() {
		logs.start();
		handlerLogger().addAppender(logs);
	}

	@AfterEach
	void detachLog() {
		handlerLogger().detachAppender(logs);
	}

	private static Logger handlerLogger() {
		return (Logger) LoggerFactory.getLogger(CoreExceptionHandler.class);
	}

	@Test
	@DisplayName("4xx CoreException - 상세 메시지를 그대로 응답, WARN 로그")
	void clientCoreException() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleCore(new CoreException(CommonErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다."));

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().getCode()).isEqualTo("C404");
		assertThat(response.getBody().getMessage()).isEqualTo("상품을 찾을 수 없습니다.");
		assertThat(logs.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	@Test
	@DisplayName("5xx CoreException - 응답 메시지는 공통 INTERNAL_SERVER 로 고정(코드/상태 유지), 상세·원인 예외는 ERROR 로그로만")
	void serverCoreException() {
		ErrorCode upstream = ErrorCode.of("PAY_502", "결제사 응답 오류입니다.", 502);
		CoreException e = new CoreException(upstream, "payment.kspay.base-url 미설정 (internal-host:8443)", new IllegalStateException("connection reset"))
			.addErrorLogData("tid", "T-1");

		ResponseEntity<ApiResponse<Void>> response = handler.handleCore(e);

		assertThat(response.getStatusCode().value()).isEqualTo(502);
		assertThat(response.getBody().getCode()).isEqualTo("PAY_502");
		assertThat(response.getBody().getMessage()).isEqualTo(CommonErrorCode.INTERNAL_SERVER.message())
												   .doesNotContain("internal-host", "결제사");
		assertThat(logs.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.ERROR);
			assertThat(event.getFormattedMessage()).contains("PAY_502", "payment.kspay.base-url 미설정", "tid=T-1");
			assertThat(event.getThrowableProxy().getClassName()).isEqualTo(CoreException.class.getName());
			assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("connection reset");
		});
	}

	@Test
	@DisplayName("벤더 코드로 만든 500 ErrorCode - 응답 메시지에 벤더 원문·내부 문구 미노출, 코드는 유지")
	void vendorServerCoreException() {
		ErrorCode vendor = ErrorCode.of("TOSS_401", "토스 원문 - UNAUTHORIZED_KEY (secret=sk_...)", 500);

		ResponseEntity<ApiResponse<Void>> response = handler.handleCore(new CoreException(vendor));

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().getCode()).isEqualTo("TOSS_401");
		assertThat(response.getBody().getMessage()).isEqualTo(CommonErrorCode.INTERNAL_SERVER.message())
												   .doesNotContain("토스", "UNAUTHORIZED_KEY", "sk_");
	}

	@Test
	@DisplayName("errorLogData 값 - 개행·탭 등 제어문자를 치환하고 값마다 200자로 잘라 로그 위조 방지")
	void sanitizeLogData() {
		CoreException e = new CoreException(CommonErrorCode.BAD_REQUEST)
			.addErrorLogData("alg", "RS256\r\n2026-10-24 INFO forged\tline\u2028")
			.addErrorLogData("long", "a".repeat(500))
			.addErrorLogData("none", null);

		handler.handleCore(e);

		String logged = logs.list.get(0).getFormattedMessage();
		assertThat(logged).doesNotContain("\r", "\n", "\t", "\u2028")
						  .contains("alg=RS256__2026-10-24 INFO forged_line_", "none=null");
		assertThat(CoreExceptionHandler.logData(e).get("long")).isEqualTo("a".repeat(CoreExceptionHandler.LOG_VALUE_MAX_LENGTH) + "…");
		assertThat(e.getErrorLogData().get("alg")).asString().contains("\r\n");
	}

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

	@Test
	@DisplayName("깨진 multipart - C400, 업로드 크기 초과 - C413 (500 아님)")
	void multipart() {
		ResponseEntity<ApiResponse<Void>> broken = handler.handleUnexpected(new MultipartException("Failed to parse multipart servlet request"));
		assertThat(broken.getStatusCode().value()).isEqualTo(400);
		assertThat(broken.getBody().getCode()).isEqualTo("C400");

		ResponseEntity<ApiResponse<Void>> tooLarge = handler.handleUnexpected(new MaxUploadSizeExceededException(1024));
		assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
		assertThat(tooLarge.getBody().getCode()).isEqualTo("C413");
	}
}
