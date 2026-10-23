package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestLogMaskAndMdcTest {

	private final RequestLogFilter filter = new RequestLogFilter(new WebProperty.RequestLog());

	@Test
	@DisplayName("마스킹 — 키가 마스킹 단어로 끝나면 문자열·숫자 값을 가림(이름·주소 포함), price·city 같은 키는 그대로")
	void mask() {
		String masked = filter.mask("{\"name\":\"홍길동\",\"newPassword\":\"p@ss\\\"word\",\"cardNo\":1234567890123456,"
			+ "\"receiverMobileNo\":\"01012345678\",\"price\":15000,\"city\":\"서울\",\"userCi\":\"CI==\"}");

		assertThat(masked).contains("\"newPassword\":\"****\"", "\"cardNo\":\"****\"", "\"receiverMobileNo\":\"****\"",
			"\"userCi\":\"****\"", "\"price\":15000", "\"city\":\"서울\"", "\"name\":\"****\"");
		assertThat(masked).doesNotContain("p@ss", "1234567890123456", "01012345678", "CI==", "홍길동");
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
