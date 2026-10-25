package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogMaskAndMdcTest {

	private final RequestLogFilter filter = new RequestLogFilter(new WebProperty.RequestLog());

	private String body(String json) {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		return filter.body(bytes, false, bytes.length);
	}

	private static RequestLogFilter includeBody(int bodyMaxLength) {
		WebProperty.RequestLog property = new WebProperty.RequestLog();
		property.setIncludeBody(true);
		property.setBodyMaxLength(bodyMaxLength);
		return new RequestLogFilter(property);
	}

	@Test
	@DisplayName("마스킹 — 키가 마스킹 단어로 끝나면 문자열·숫자 값을 가림(이름·주소 포함), price·city 같은 키는 그대로")
	void mask() {
		String masked = body("{\"name\":\"홍길동\",\"newPassword\":\"p@ss\\\"word\",\"cardNo\":1234567890123456,"
			+ "\"receiverMobileNo\":\"01012345678\",\"price\":15000,\"city\":\"서울\",\"userCi\":\"CI==\"}");

		assertThat(masked).contains("\"newPassword\":\"***\"", "\"cardNo\":\"***\"", "\"receiverMobileNo\":\"***\"",
			"\"userCi\":\"***\"", "\"price\":15000", "\"city\":\"서울\"", "\"name\":\"***\"");
		assertThat(masked).doesNotContain("p@ss", "1234567890123456", "01012345678", "CI==", "홍길동");
	}

	@Test
	@DisplayName("마스킹 — snake_case·kebab-case 키도 캐멀 매스킹 단어와 매칭 (phone_number · phone-number)")
	void maskSnakeAndKebabCase() {
		String masked = body("{\"phone_number\":\"010-1111-2222\",\"card-no\":\"4111\",\"first_name\":\"길동\",\"User_Ci\":\"CI==\",\"price_won\":15000}");

		assertThat(masked).contains("\"phone_number\":\"***\"", "\"card-no\":\"***\"", "\"first_name\":\"***\"",
			"\"User_Ci\":\"***\"", "\"price_won\":15000");
		assertThat(masked).doesNotContain("010-1111-2222", "4111", "길동", "CI==");
	}

	@Test
	@DisplayName("마스킹 — 중첩 객체 안의 phoneNumber(대소문자 무시), 값이 배열·객체면 통째로, 배열 안 객체의 키도")
	void maskNested() {
		String masked = body("{\"member\":{\"PhoneNumber\":\"010-1234-5678\",\"age\":30},\"apiKey\":[\"k-1\",\"k-2\"],"
			+ "\"password\":{\"old\":\"a1\",\"new\":\"b2\"},\"cards\":[{\"cardNo\":\"1111\"},{\"cardNo\":\"2222\",\"alias\":\"main\"}],"
			+ "\"otpauthUri\":\"otpauth://totp/x?secret=ABC\",\"authCode\":\"123456\"}");

		assertThat(masked).isEqualTo("{\"member\":{\"PhoneNumber\":\"***\",\"age\":30},\"apiKey\":\"***\",\"password\":\"***\","
			+ "\"cards\":[{\"cardNo\":\"***\"},{\"cardNo\":\"***\",\"alias\":\"main\"}],\"otpauthUri\":\"***\",\"authCode\":\"***\"}");
	}

	@Test
	@DisplayName("잘렸거나 JSON 이 아닌 본문 — 내용 없이 크기만, 빈 본문은 -")
	void truncatedOrBroken() {
		byte[] cut = "{\"filler\":\"x\",\"password\":\"secret-pa".getBytes(StandardCharsets.UTF_8);

		assertThat(filter.body(cut, true, 3000)).isEqualTo("(3000 bytes)");
		assertThat(filter.body(cut, false, cut.length)).isEqualTo("(" + cut.length + " bytes)");
		assertThat(filter.body(new byte[0], false, -1)).isEqualTo("-");
	}

	@Test
	@DisplayName("요청 본문이 상한을 넘으면 경계에서 잘린 password 도 찍지 않는다 — 앱은 본문 전체를 받는다")
	void truncatedRequest(CapturedOutput output) throws Exception {
		String json = "{\"filler\":\"" + "x".repeat(26) + "\",\"password\":\"secret-password-123\"}";
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		AtomicReference<String> received = new AtomicReference<>();

		includeBody(60).doFilter(request, new MockHttpServletResponse(),
			(req, res) -> received.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

		assertThat(received.get()).isEqualTo(json);
		assertThat(output.getOut()).contains("POST /api/login 200", "req=(" + json.length() + " bytes)").doesNotContain("secret-pa");
	}

	@Test
	@DisplayName("다운로드·SSE 응답은 래핑·버퍼링하지 않는다 — flush 하면 바로 나가고 크기만 찍는다")
	void streamingNotBuffered(CapturedOutput output) throws Exception {
		for (String contentType : new String[] {"application/octet-stream", "text/event-stream"}) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			AtomicBoolean committed = new AtomicBoolean();
			AtomicBoolean cached = new AtomicBoolean();

			includeBody(2000).doFilter(new MockHttpServletRequest("GET", "/api/files/download"), response, (req, res) -> {
				cached.set(res instanceof ContentCachingResponseWrapper);
				res.setContentType(contentType);
				res.setContentLength(5);
				res.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
				res.getOutputStream().flush();
				committed.set(response.isCommitted());
			});

			assertThat(cached.get()).isFalse();
			assertThat(committed.get()).as(contentType).isTrue();
			assertThat(response.getContentAsString()).isEqualTo("hello");
		}
		assertThat(output.getOut()).contains("GET /api/files/download 200", "res=(5 bytes)");
	}

	@Test
	@DisplayName("JSON 응답도 흘려보내며 앞부분만 복사 — 상한 이하면 마스킹해 찍고, 넘으면 크기만")
	void jsonResponse(CapturedOutput output) throws Exception {
		String small = "{\"accessToken\":\"tk-1\",\"id\":7}";
		String large = "{\"accessToken\":\"" + "t".repeat(100) + "\"}";
		for (String json : new String[] {small, large}) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			AtomicBoolean committed = new AtomicBoolean();

			includeBody(60).doFilter(new MockHttpServletRequest("GET", "/api/token"), response, (req, res) -> {
				res.setContentType("application/json");
				writeInChunks(res.getOutputStream(), json.getBytes(StandardCharsets.UTF_8));
				res.flushBuffer();
				committed.set(response.isCommitted());
			});

			assertThat(committed.get()).isTrue();
			assertThat(response.getContentAsString()).isEqualTo(json);
		}
		assertThat(output.getOut()).contains("res={\"accessToken\":\"***\",\"id\":7}", "res=(" + large.length() + " bytes)")
									.doesNotContain("tk-1", "tttt");
	}

	private static void writeInChunks(ServletOutputStream out, byte[] bytes) throws IOException {
		out.write(bytes[0]);
		out.write(bytes, 1, bytes.length - 1);
	}

	@Test
	@DisplayName("비동기 MDC — 호출 스레드의 traceId 가 작업 스레드로 넘어가고, 끝나면 작업 스레드는 원래대로")
	void mdcDecorator() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			MdcTaskDecorator decorator = new MdcTaskDecorator();
			AtomicReference<String> seen = new AtomicReference<>();
			MDC.put(TraceIdFilter.MDC_KEY, "trace-abc-123");

			CompletableFuture.runAsync(decorator.decorate(() -> seen.set(MDC.get(TraceIdFilter.MDC_KEY))), pool).get();
			MDC.clear();
			AtomicReference<String> after = new AtomicReference<>("not-run");
			CompletableFuture.runAsync(() -> after.set(MDC.get(TraceIdFilter.MDC_KEY)), pool).get();

			assertThat(seen.get()).isEqualTo("trace-abc-123");
			assertThat(after.get()).isNull();
		} finally {
			MDC.clear();
			pool.shutdown();
		}
	}
}
