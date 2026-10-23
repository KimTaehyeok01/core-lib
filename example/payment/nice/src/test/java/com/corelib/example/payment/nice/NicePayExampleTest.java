package com.corelib.example.payment.nice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class NicePayExampleTest {

	static final MockHttpServer NICEPAY = MockHttpServer.start();
	private static final String CLIENT_KEY = "S2_test_client";
	private static final String SECRET_KEY = "test_secret_dummy";
	private static final String TID = "nicuntct1m0101231018100000000001";
	private static final String EDI_DATE = "2026-10-18T10:00:00.000+0900";

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("payment.nice.base-url", NICEPAY::baseUrl);
		registry.add("payment.nice.client-key", () -> CLIENT_KEY);
		registry.add("payment.nice.secret-key", () -> SECRET_KEY);
		registry.add("payment.nice.read-timeout", () -> "1s");
	}

	@AfterAll
	static void closeServer() {
		NICEPAY.close();
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private String createOrder(int amount) throws Exception {
		String body = mockMvc.perform(post("/api/nicepay/orders/create").contentType(MediaType.APPLICATION_JSON)
																		.content("{\"goodsName\":\"머그컵\",\"amount\":" + amount + "}"))
							 .andExpect(jsonPath("$.data.clientId").value(CLIENT_KEY))
							 .andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.orderId");
	}

	/** NICEPAY 가 returnUrl 로 보내는 form POST 를 흉내낸다 — 서명은 매뉴얼 규칙 hex(sha256(authToken+clientId+amount+secretKey)) */
	private static MockHttpServletRequestBuilder returnPost(String orderId, int amount, String signature) {
		return post("/nicepay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
									  .param("authResultCode", "0000")
									  .param("authResultMsg", "인증 성공")
									  .param("tid", TID)
									  .param("clientId", CLIENT_KEY)
									  .param("orderId", orderId)
									  .param("amount", String.valueOf(amount))
									  .param("authToken", "auth-token-1")
									  .param("signature", signature);
	}

	private static String authSignature(int amount) throws Exception {
		return sha256("auth-token-1" + CLIENT_KEY + amount + SECRET_KEY);
	}

	private static String payment(String orderId, String status, int amount, int balance) throws Exception {
		return "{\"resultCode\":\"0000\",\"resultMsg\":\"정상 처리되었습니다.\",\"tid\":\"" + TID + "\",\"orderId\":\"" + orderId + "\","
			+ "\"ediDate\":\"" + EDI_DATE + "\",\"signature\":\"" + sha256(TID + amount + EDI_DATE + SECRET_KEY) + "\","
			+ "\"status\":\"" + status + "\",\"payMethod\":\"card\",\"amount\":" + amount + ",\"balanceAmt\":" + balance
			+ ",\"paidAt\":\"2026-10-18T10:00:00.000+0900\"}";
	}

	@Test
	@DisplayName("주문 → returnUrl 검증·승인 → 부분취소 → 전액취소")
	void approveAndCancel() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();
		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "paid", 12000, 12000)));

		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(NICEPAY.request(before).path()).isEqualTo("/v1/payments/" + TID);
		assertThat(NICEPAY.request(before).body()).contains("\"amount\":12000");
		mockMvc.perform(get("/api/nicepay/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.tid").value(TID));

		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "partialCancelled", 12000, 7000)));
		mockMvc.perform(post("/api/nicepay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														  .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"부분 환불\",\"cancelAmt\":5000}"))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmt").value(7000));
		assertThat(NICEPAY.request(before + 1).path()).isEqualTo("/v1/payments/" + TID + "/cancel");

		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "cancelled", 12000, 0)));
		mockMvc.perform(post("/api/nicepay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														  .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"고객 변심\"}"))
			   .andExpect(jsonPath("$.data.status").value("CANCELED"));
	}

	@Test
	@DisplayName("인증결과 서명 위변조 - 승인 호출 없이 실패")
	void tamperedSignature() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();

		mockMvc.perform(returnPost(orderId, 12000, sha256("forged")))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=NICEPAY-SIGNATURE")));
		assertThat(NICEPAY.requestCount()).isEqualTo(before);
		mockMvc.perform(get("/api/nicepay/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));
	}

	@Test
	@DisplayName("인증결과 금액이 주문과 다름 - 승인 호출 없이 실패")
	void tamperedAmount() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();

		mockMvc.perform(returnPost(orderId, 100, authSignature(100)))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=NICEPAY-AUTH-MISMATCH")));
		assertThat(NICEPAY.requestCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("승인 결과불명(5xx) - 모듈이 망취소, 결제 미성립으로 실패 처리")
	void netCancelled() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();
		NICEPAY.enqueue(MockResponse.status(500, "{}"));
		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "cancelled", 12000, 0)));

		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", containsString("code=NICEPAY-NET-CANCELLED")));
		assertThat(NICEPAY.request(before + 1).path()).isEqualTo("/v1/payments/netcancel");
		mockMvc.perform(get("/api/nicepay/orders/detail").param("orderId", orderId))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));
	}

	@Test
	@DisplayName("사용자가 결제창을 닫음(authResultCode != 0000) - 실패 화면으로")
	void userCanceled() throws Exception {
		String orderId = createOrder(12000);

		mockMvc.perform(post("/nicepay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											   .param("authResultCode", "9999").param("authResultMsg", "사용자 취소")
											   .param("orderId", orderId).param("clientId", CLIENT_KEY))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=NICEPAY_9999")));
	}

	@Test
	@DisplayName("기동 안내 - NicePayUtils 보고")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("NicePayUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isEqualTo(true);
		assertThat(report.getDetail()).contains("base-url=http://127.0.0.1");
	}
}
