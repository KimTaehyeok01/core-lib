package com.corelib.webclient;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import io.netty.channel.ChannelOption;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 벤더 연동 모듈(payment, auth 등)이 공통으로 쓰는 WebClient 도구.
 * <ul>
 *   <li>timeout 없는 클라이언트는 만들지 않는다 — 벤더 장애가 우리 스레드를 무한정 붙잡지 않게</li>
 *   <li>커넥션 풀(상한·유휴·수명·대기 timeout)과 응답 본문 메모리 상한을 기본으로 건다</li>
 *   <li>호출은 Mono 로 돌려준다 — MVC 앱은 {@code .block()}, WebFlux 앱은 그대로 조합한다</li>
 *   <li>실패는 결과확정(REJECTED·NOT_SENT)과 결과불명(UNKNOWN)으로만 나눈다 — 후속 조치(재조회·망취소)는 호출측이 정한다</li>
 * </ul>
 * @implNote WebFlux 이벤트 루프 스레드에서 {@code .block()} 하면 IllegalStateException 이다 — 리액티브 앱은 Mono 를 그대로 쓴다
 */
public final class WebClientUtils {

	/** 응답 본문을 메모리에 모으는 상한 — Spring 기본 256KB 는 목록·조회 응답에 작다. 넘으면 본문 해석 실패(UNKNOWN) */
	static final int MAX_IN_MEMORY_SIZE = 2 * 1024 * 1024;
	/** 벤더(원격 주소)별 최대 연결 수 — 넘는 요청은 connectTimeout 만큼 풀에서 기다리다 NOT_SENT */
	static final int MAX_CONNECTIONS = 100;
	/** 벤더 LB·서버가 유휴 연결을 먼저 끊기 전에 버린다 — 끊긴 연결 재사용(응답 전 연결 종료) 방지 */
	private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(20);
	/** 오래된 연결을 주기적으로 새로 맺는다 — 벤더 IP 변경(DNS)을 따라간다 */
	private static final Duration MAX_LIFE_TIME = Duration.ofSeconds(60);
	private static final Duration EVICT_INTERVAL = Duration.ofSeconds(30);
	/**
	 * 풀 대기 timeout 을 connectTimeout 과 같게 두므로 connectTimeout 값마다 풀을 1개만 만들어 공유한다.
	 * 클라이언트(빌더 호출)마다 풀을 만들면 연결·백그라운드 정리 작업이 쌓인다. 풀 안에서는 원격 주소별로 연결을 나눈다
	 */
	private static final Map<Duration, ConnectionProvider> PROVIDERS = new ConcurrentHashMap<>();
	/**
	 * 풀에서 연결을 못 받은 실패(대기 timeout·대기열 초과) — 요청이 나가지 않았다.
	 * reactor-netty 가 reactor-pool 을 내부 패키지(reactor.netty.internal.shaded)로 넣어 두어 버전에 따라 패키지가 바뀔 수 있다 — 클래스 대신 이름으로 비교한다
	 */
	private static final Set<String> POOL_ACQUIRE_FAILURES = Set.of("PoolAcquireTimeoutException", "PoolAcquirePendingLimitException");

	private WebClientUtils() {
	}

	/**
	 * baseUrl 과 timeout 이 설정된 WebClient 빌더를 만든다 — 인증 헤더 등은 호출측이 이어서 붙인다.
	 *
	 * 응답 본문 메모리 상한은 2MB — 더 필요하면 반환된 빌더에 {@code .codecs(c -> c.defaultCodecs().maxInMemorySize(n))} 로 덮어쓴다.
	 *
	 * @param connectTimeout TCP 연결 timeout, 커넥션 풀 대기 timeout — 초과는 요청 미전송(NOT_SENT)
	 * @param readTimeout    응답 timeout — 초과는 결과불명(UNKNOWN)
	 * @throws CoreException baseUrl 누락, timeout 이 null·0 이하 (500, 설정 오류)
	 */
	public static WebClient.Builder builder(String baseUrl, Duration connectTimeout, Duration readTimeout) {
		if (StringUtils.isBlank(baseUrl)) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "WebClient baseUrl 이 없습니다.");
		}
		requirePositive(connectTimeout, "connectTimeout");
		requirePositive(readTimeout, "readTimeout");
		return builder(baseUrl, connectTimeout, readTimeout, connectionProvider(connectTimeout));
	}

	/** 커넥션 풀을 직접 넘긴다 — 풀 고갈 재현 테스트용. 설정 검증은 공개 builder 가 한다 */
	static WebClient.Builder builder(String baseUrl, Duration connectTimeout, Duration readTimeout, ConnectionProvider provider) {
		HttpClient httpClient = HttpClient.create(provider)
										  .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
										  .responseTimeout(readTimeout);

		String normalized = baseUrl.trim();
		// 끝의 / 를 떼어 경로 결합 시 // 가 생기지 않게 한다.
		return WebClient.builder()
						.baseUrl(normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized)
						.clientConnector(new ReactorClientHttpConnector(httpClient))
						.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE));
	}

	/** connectTimeout 별 공유 커넥션 풀 — 같은 값이면 같은 풀을 돌려준다 */
	static ConnectionProvider connectionProvider(Duration connectTimeout) {
		return PROVIDERS.computeIfAbsent(connectTimeout, WebClientUtils::newProvider);
	}

	private static ConnectionProvider newProvider(Duration pendingAcquireTimeout) {
		return ConnectionProvider.builder("core-webclient-" + pendingAcquireTimeout.toMillis() + "ms")
								 .maxConnections(MAX_CONNECTIONS)
								 .maxIdleTime(MAX_IDLE_TIME)
								 .maxLifeTime(MAX_LIFE_TIME)
								 .pendingAcquireTimeout(pendingAcquireTimeout)
								 .evictInBackground(EVICT_INTERVAL)
								 .build();
	}

	/**
	 * JSON POST 후 응답 본문을 받는다 — Content-Type 기본은 application/json, headers 에서 덮어쓸 수 있다.
	 *
	 * @param headers 요청별 헤더 (인증 등) — 없으면 null
	 */
	public static <T> Mono<T> post(WebClient client, String uri, Consumer<HttpHeaders> headers, Object body,
								   Class<T> responseType, Object... uriVariables) {
		WebClient.RequestBodySpec request = client.post()
												  .uri(uri, uriVariables)
												  .contentType(MediaType.APPLICATION_JSON)
												  .headers(headers == null ? h -> {} : headers);
		WebClient.RequestHeadersSpec<?> withBody = body == null ? request : request.bodyValue(body);
		return withBody.retrieve().bodyToMono(responseType);
	}

	/** GET 후 응답 본문을 받는다 */
	public static <T> Mono<T> get(WebClient client, String uri, Consumer<HttpHeaders> headers, Class<T> responseType,
								  Object... uriVariables) {
		return client.get()
					 .uri(uri, uriVariables)
					 .headers(headers == null ? h -> {} : headers)
					 .retrieve()
					 .bodyToMono(responseType);
	}

	/**
	 * 호출 실패를 분류한다 — 원인 체인을 따라가며 가장 먼저 판정되는 것을 쓴다.
	 * @implNote netty 의 연결 timeout(ConnectTimeoutException)과 연결 거부(AnnotatedConnectException)는 ConnectException 하위라 NOT_SENT,
	 *           커넥션 풀 대기 timeout·대기열 초과도 연결을 못 받은 것이라 NOT_SENT,
	 *           응답 timeout(ReadTimeoutException)·응답 전 연결 종료(PrematureCloseException)는 여기에 걸리지 않아 UNKNOWN 이 된다
	 */
	public static FailureType classify(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof WebClientResponseException e) {
				return e.getStatusCode().is4xxClientError() ? FailureType.REJECTED : FailureType.UNKNOWN;
			}
			if (cause instanceof ConnectException || cause instanceof UnknownHostException
					|| cause instanceof SSLHandshakeException || POOL_ACQUIRE_FAILURES.contains(cause.getClass().getSimpleName())) {
				return FailureType.NOT_SENT;
			}
		}
		return FailureType.UNKNOWN;
	}

	/** 4xx 응답 예외를 꺼낸다 — 벤더 오류 본문(getResponseBodyAs)을 읽을 때 쓴다 */
	public static Optional<WebClientResponseException> findClientError(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof WebClientResponseException e && e.getStatusCode().is4xxClientError()) {
				return Optional.of(e);
			}
		}
		return Optional.empty();
	}

	private static void requirePositive(Duration timeout, String name) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "WebClient " + name + " 은 0보다 커야 합니다.");
		}
	}
}
