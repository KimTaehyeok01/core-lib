package com.corelib.webclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.corelib.common.exception.CoreException;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class WebClientUtilsTest {

	private MockHttpServer server;
	private WebClient client;

	@BeforeEach
	void setUp() {
		server = MockHttpServer.start();
		client = WebClientUtils.builder(server.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500)).build();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	@DisplayName("POST - JSON 본문·헤더·경로 변수 전송, 응답 본문 해석")
	void post() {
		server.enqueue(MockResponse.ok("{\"result\":\"ok\"}"));

		Map<?, ?> response = WebClientUtils.post(client, "/v1/items/{id}", h -> h.set("X-Trace", "t-1"),
			Map.of("amount", 1000), Map.class, "item-1").block();

		assertThat(response.get("result")).isEqualTo("ok");
		assertThat(server.request(0).method()).isEqualTo("POST");
		assertThat(server.request(0).path()).isEqualTo("/v1/items/item-1");
		assertThat(server.request(0).header("X-Trace")).isEqualTo("t-1");
		assertThat(server.request(0).header("Content-Type")).contains("application/json");
		assertThat(server.request(0).body()).isEqualTo("{\"amount\":1000}");
	}

	@Test
	@DisplayName("4xx - REJECTED, 오류 본문을 꺼낼 수 있음")
	void clientError() {
		server.enqueue(MockResponse.status(400, "{\"code\":\"BAD\"}"));

		Throwable error = catchThrowable(() -> WebClientUtils.get(client, "/x", null, Map.class).block());

		assertThat(WebClientUtils.classify(error)).isEqualTo(FailureType.REJECTED);
		assertThat(WebClientUtils.findClientError(error)).hasValueSatisfying(e ->
			assertThat(e.getResponseBodyAs(Map.class)).containsEntry("code", "BAD"));
	}

	@Test
	@DisplayName("5xx - UNKNOWN (결과불명), 4xx 예외로 꺼내지지 않음")
	void serverError() {
		server.enqueue(MockResponse.status(502, "{}"));

		Throwable error = catchThrowable(() -> WebClientUtils.get(client, "/x", null, Map.class).block());

		assertThat(WebClientUtils.classify(error)).isEqualTo(FailureType.UNKNOWN);
		assertThat(WebClientUtils.findClientError(error)).isEmpty();
	}

	@Test
	@DisplayName("응답 timeout - UNKNOWN (요청은 갔지만 결과를 모름)")
	void readTimeout() {
		server.enqueue(MockResponse.ok("{}").delayed(Duration.ofSeconds(3)));

		Throwable error = catchThrowable(() -> WebClientUtils.get(client, "/slow", null, Map.class).block());

		assertThat(error).isNotNull();
		assertThat(WebClientUtils.classify(error)).isEqualTo(FailureType.UNKNOWN);
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("연결 거부 - NOT_SENT (요청 미전송, 결과확정)")
	void connectionRefused() {
		WebClient closed = WebClientUtils.builder(MockHttpServer.closedPortUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1)).build();

		Throwable error = catchThrowable(() -> WebClientUtils.get(closed, "/x", null, Map.class).block());

		assertThat(WebClientUtils.classify(error)).isEqualTo(FailureType.NOT_SENT);
		assertThat(FailureType.NOT_SENT.isConfirmed()).isTrue();
	}

	@Test
	@DisplayName("timeout 0 이하·baseUrl 누락은 설정 오류")
	void invalidSettings() {
		assertThatThrownBy(() -> WebClientUtils.builder("https://api.example.com", Duration.ZERO, Duration.ofSeconds(1)))
			.isInstanceOf(CoreException.class);
		assertThatThrownBy(() -> WebClientUtils.builder(" ", Duration.ofSeconds(1), Duration.ofSeconds(1)))
			.isInstanceOf(CoreException.class);
	}
}
