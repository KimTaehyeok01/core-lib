package com.corelib.example.payment.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class TossExampleTest {

	static final MockHttpServer TOSS = MockHttpServer.start();

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("payment.toss.base-url", TOSS::baseUrl);
		registry.add("payment.toss.secret-key", () -> "test_sk_dummy");
		registry.add("payment.toss.read-timeout", () -> "1s");
		registry.add("example.toss.client-key", () -> "test_ck_dummy");
	}

	@AfterAll
	static void closeServer() {
		TOSS.close();
	}

	private String createOrder(int amount) throws Exception {
		String body = mockMvc.perform(post("/api/toss/orders/create").contentType(MediaType.APPLICATION_JSON)
																	 .content("{\"orderName\":\"티셔츠\",\"amount\":" + amount + "}"))
							 .andExpect(jsonPath("$.data.clientKey").value("test_ck_dummy"))
							 .andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.orderId");
	}

	private static String payment(String orderId, String status, int amount, int balance) {
		return "{\"paymentKey\":\"pk_1\",\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\",\"method\":\"카드\","
			+ "\"totalAmount\":" + amount + ",\"balanceAmount\":" + balance + ",\"approvedAt\":\"2026-10-18T10:00:00+09:00\"}";
	}

	@Test
	@DisplayName("주문 → 승인 → 부분취소 → 전액취소, 승인에는 서버 금액 사용")
	void confirmAndCancel() throws Exception {
		String orderId = createOrder(15000);
		int before = TOSS.requestCount();
		TOSS.enqueue(MockResponse.ok(payment(orderId, "DONE", 15000, 15000)));

		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(TOSS.request(before).body()).contains("\"amount\":15000", "\"orderId\":\"" + orderId + "\"");
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.paymentKey").value("pk_1"));

		// 새로고침 재진입 - 승인 완료 주문은 결과 화면, 토스 재호출 없음
		int afterConfirm = TOSS.requestCount();
		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(TOSS.requestCount()).isEqualTo(afterConfirm);

		TOSS.enqueue(MockResponse.ok(payment(orderId, "PARTIAL_CANCELED", 15000, 10000)));
		mockMvc.perform(post("/api/toss/orders/cancel").contentType(MediaType.APPLICATION_JSON)
													   .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"부분 환불\",\"cancelAmount\":5000}"))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(10000));

		TOSS.enqueue(MockResponse.ok(payment(orderId, "CANCELED", 15000, 0)));
		mockMvc.perform(post("/api/toss/orders/cancel").contentType(MediaType.APPLICATION_JSON)
													   .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"고객 변심\"}"))
			   .andExpect(jsonPath("$.data.status").value("CANCELED"));

		// 취소된 주문의 재진입 - 결제가 성립했던 주문이라 결과 화면에서 상태 조회
		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
	}

	@Test
	@DisplayName("토스 승인 거절(4xx) - 콜백에 서명이 없어 FAILED 대신 READY 로 되돌림, 화면은 고정 문구 → 재승인 가능")
	void rejectedKeepsReady() throws Exception {
		String orderId = createOrder(15000);
		TOSS.enqueue(MockResponse.status(404, "{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}"));

		String location = mockMvc.perform(get("/toss/success").param("paymentKey", "pk_fake").param("orderId", orderId).param("amount", "15000"))
								 .andReturn().getResponse().getHeader("Location");
		assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8))
			.startsWith("/fail.html")
			.contains("결제가 승인되지 않았습니다.")
			.doesNotContain("존재하지 않는 결제 정보");
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));

		TOSS.enqueue(MockResponse.ok(payment(orderId, "DONE", 15000, 15000)));
		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"));
	}

	@Test
	@DisplayName("successUrl 금액 위변조 - 토스 호출 없이 실패 화면, 서명 없는 값이라 READY 유지 → 정상 콜백으로 재승인 가능")
	void tamperedAmount() throws Exception {
		String orderId = createOrder(15000);
		int before = TOSS.requestCount();

		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "100"))
			   .andExpect(header().string("Location", startsWith("/fail.html")));
		assertThat(TOSS.requestCount()).isEqualTo(before);
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));

		TOSS.enqueue(MockResponse.ok(payment(orderId, "DONE", 15000, 15000)));
		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
	}

	@Test
	@DisplayName("승인 콜백 동시 2건 - 선점한 1건만 토스 승인 호출, PAID·paymentKey 유지")
	void concurrentConfirm() throws Exception {
		String orderId = createOrder(15000);
		int before = TOSS.requestCount();
		// 승인 응답을 늦춰 첫 요청이 토스 호출 중일 때 두 번째 콜백이 들어오게 한다
		TOSS.enqueue(MockResponse.ok(payment(orderId, "DONE", 15000, 15000)).delayed(Duration.ofMillis(500)));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<String> locations = new ArrayList<>();
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
								  .andReturn().getResponse().getHeader("Location");
				}));
			}
			start.countDown();
			for (Future<String> future : futures) {
				locations.add(future.get(10, TimeUnit.SECONDS));
			}
		} finally {
			executor.shutdownNow();
		}

		// 패자는 PROCESSING 또는 PAID 를 보고 결과 화면으로 간다
		assertThat(locations).containsOnly("/result.html?orderId=" + orderId);
		assertThat(TOSS.requestCount()).isEqualTo(before + 1);
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.paymentKey").value("pk_1"));
	}

	@Test
	@DisplayName("취소 timeout - CANCEL_UNKNOWN 으로 기록, 재취소는 토스 호출 없이 400")
	void cancelResultUnknown() throws Exception {
		String orderId = createOrder(15000);
		TOSS.enqueue(MockResponse.ok(payment(orderId, "DONE", 15000, 15000)));
		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));

		int before = TOSS.requestCount();
		TOSS.enqueue(MockResponse.ok(payment(orderId, "CANCELED", 15000, 0)).delayed(Duration.ofSeconds(3)));
		mockMvc.perform(post("/api/toss/orders/cancel").contentType(MediaType.APPLICATION_JSON)
													   .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"고객 변심\"}"))
			   .andExpect(jsonPath("$.code").value("TOSS-UNKNOWN"));
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("CANCEL_UNKNOWN"))
			   .andExpect(jsonPath("$.data.paymentKey").value("pk_1"));

		mockMvc.perform(post("/api/toss/orders/cancel").contentType(MediaType.APPLICATION_JSON)
													   .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"고객 변심\"}"))
			   .andExpect(status().isBadRequest());
		assertThat(TOSS.requestCount()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("successUrl 파라미터 누락·형식 오류 - JSON 400 이 아니라 실패 화면, 토스 호출 없음")
	void invalidCallbackParams() throws Exception {
		String orderId = createOrder(15000);
		int before = TOSS.requestCount();

		mockMvc.perform(get("/toss/success"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html")));
		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "abc"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html")));
		// 선점 뒤 모듈 요청 검증(@NotBlank)에서 거절되는 값 - PROCESSING 에서 READY 로 되돌린다
		mockMvc.perform(get("/toss/success").param("paymentKey", "\u0000").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html")));
		assertThat(TOSS.requestCount()).isEqualTo(before);
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));
	}

	@Test
	@DisplayName("failUrl 은 서명 없는 브라우저 입력 - 주문은 READY 유지, 화면 문구는 쿼리 message 가 아닌 서버 고정 문구")
	void failUrlKeepsReady() throws Exception {
		String orderId = createOrder(15000);

		String location = mockMvc.perform(get("/toss/fail").param("code", "REJECT_CARD_COMPANY").param("message", "고객센터로 연락하세요")
														   .param("orderId", orderId))
								 .andReturn().getResponse().getHeader("Location");
		assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8))
			.startsWith("/fail.html?code=REJECT_CARD_COMPANY")
			.contains("결제가 완료되지 않았습니다.")
			.doesNotContain("고객센터로 연락하세요");
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));
	}

	@Test
	@DisplayName("승인 결과불명(5xx → 재조회도 5xx) - 실패로 단정하지 않고 UNKNOWN")
	void resultUnknown() throws Exception {
		String orderId = createOrder(15000);
		TOSS.enqueue(MockResponse.status(500, "{}"));
		TOSS.enqueue(MockResponse.status(503, "{}"));

		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "15000"))
			   .andExpect(header().string("Location", startsWith("/fail.html")));
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
	}

	@Test
	@DisplayName("사용자가 결제창을 닫음(failUrl) - 실패 화면으로")
	void userCanceled() throws Exception {
		mockMvc.perform(get("/toss/fail").param("code", "PAY_PROCESS_CANCELED").param("message", "사용자가 결제를 취소하였습니다"))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=PAY_PROCESS_CANCELED")));
	}

	@Test
	@DisplayName("기동 안내 - TossPayUtils 보고")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("TossPayUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isEqualTo(true);
		assertThat(report.getDetail()).contains("테스트 키");
	}
}
