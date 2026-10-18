// 테스트용 로컬 HTTP 서버 - 응답을 순서대로 내주고 받은 요청을 기록한다 (reactor-netty, 추가 의존성 없음)
package com.corelib.webclient.fixture;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 실제 소켓으로 동작하는 가짜 벤더 서버 — WebClient 의 timeout·연결 거부·4xx/5xx 를 실제 네트워크 경로로 재현한다.
 * <pre>{@code
 * try (MockHttpServer server = MockHttpServer.start()) {
 *     server.enqueue(MockResponse.ok("{\"resultCode\":\"0000\"}"));
 *     ... server.baseUrl() 로 WebClient 생성 후 호출 ...
 *     assertThat(server.request(0).body()).contains("amount");
 * }
 * }</pre>
 */
public final class MockHttpServer implements AutoCloseable {

	private final Queue<MockResponse> responses = new ConcurrentLinkedQueue<>();
	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
	private final DisposableServer server;

	private MockHttpServer() {
		this.server = HttpServer.create()
								.host("127.0.0.1")
								.port(0)
								.handle((request, response) -> request.receive().aggregate().asString(StandardCharsets.UTF_8)
									.defaultIfEmpty("")
									.flatMap(body -> {
										Map<String, String> headers = new LinkedHashMap<>();
										request.requestHeaders().forEach(h -> headers.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));
										requests.add(new RecordedRequest(request.method().name(), request.uri(), headers, body));

										MockResponse next = responses.poll();
										MockResponse reply = next != null ? next : MockResponse.status(500, "{\"error\":\"no response enqueued\"}");
										Mono<Void> send = response.status(reply.status())
																  .header("Content-Type", reply.contentType())
																  .sendByteArray(Mono.just(reply.body()))
																  .then();
										return reply.delay().isZero() ? send : Mono.delay(reply.delay()).then(send);
									}))
								.bindNow();
	}

	public static MockHttpServer start() {
		return new MockHttpServer();
	}

	/** 비어 있는 포트의 URL — 연결 거부(NOT_SENT) 재현용. 잠깐 열었다 닫은 포트라 대개 비어 있다 */
	public static String closedPortUrl() {
		try (MockHttpServer temporary = start()) {
			return temporary.baseUrl();
		}
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.port();
	}

	/** 다음 요청에 돌려줄 응답을 쌓는다 — 요청 순서대로 하나씩 꺼낸다 */
	public MockHttpServer enqueue(MockResponse response) {
		responses.add(response);
		return this;
	}

	public RecordedRequest request(int index) {
		return requests.get(index);
	}

	public int requestCount() {
		return requests.size();
	}

	@Override
	public void close() {
		server.disposeNow();
	}

	/** 돌려줄 응답 — delay 를 주면 응답 timeout 을 재현한다 */
	public record MockResponse(int status, byte[] body, String contentType, Duration delay) {

		private static final String JSON = "application/json;charset=UTF-8";

		public static MockResponse ok(String json) {
			return status(200, json);
		}

		public static MockResponse status(int status, String json) {
			return new MockResponse(status, json.getBytes(StandardCharsets.UTF_8), JSON, Duration.ZERO);
		}

		/** JSON 이 아닌 응답(예: MS949 백틱 응답)을 바이트 그대로 돌려줄 때 */
		public static MockResponse bytes(int status, byte[] body, String contentType) {
			return new MockResponse(status, body, contentType, Duration.ZERO);
		}

		public MockResponse delayed(Duration delay) {
			return new MockResponse(status, body, contentType, delay);
		}
	}

	/** 받은 요청 — 헤더 이름은 소문자로 저장한다 */
	public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {

		public String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}
	}
}
