package com.corelib.example.payment.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.util.List;
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

		TOSS.enqueue(MockResponse.ok(payment(orderId, "PARTIAL_CANCELED", 15000, 10000)));
		mockMvc.perform(post("/api/toss/orders/cancel").contentType(MediaType.APPLICATION_JSON)
													   .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"부분 환불\",\"cancelAmount\":5000}"))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(10000));

		TOSS.enqueue(MockResponse.ok(payment(orderId, "CANCELED", 15000, 0)));
		mockMvc.perform(post("/api/toss/orders/cancel").contentType(MediaType.APPLICATION_JSON)
													   .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"고객 변심\"}"))
			   .andExpect(jsonPath("$.data.status").value("CANCELED"));
	}

	@Test
	@DisplayName("successUrl 금액 위변조 - 토스 호출 없이 실패 처리")
	void tamperedAmount() throws Exception {
		String orderId = createOrder(15000);
		int before = TOSS.requestCount();

		mockMvc.perform(get("/toss/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "100"))
			   .andExpect(header().string("Location", startsWith("/fail.html")));
		assertThat(TOSS.requestCount()).isEqualTo(before);
		mockMvc.perform(get("/api/toss/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));
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
