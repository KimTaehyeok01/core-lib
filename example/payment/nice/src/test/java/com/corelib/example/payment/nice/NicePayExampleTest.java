package com.corelib.example.payment.nice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.util.List;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

	/** 주문 생성 → 승인까지 마친 PAID 주문 */
	private String paidOrder(int amount) throws Exception {
		String orderId = createOrder(amount);
		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "paid", amount, amount)));
		mockMvc.perform(returnPost(orderId, amount, authSignature(amount)))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		return orderId;
	}

	/** cancelAmt 가 null 이면 전액취소 */
	private static MockHttpServletRequestBuilder cancelPost(String orderId, Integer cancelAmt) {
		return post("/api/nicepay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
												 .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"고객 변심\""
													 + (cancelAmt == null ? "" : ",\"cancelAmt\":" + cancelAmt) + "}");
	}

	private MockHttpServletRequestBuilder detailGet(String orderId) {
		return get("/api/nicepay/orders/detail").param("orderId", orderId);
	}

	@Test
	@DisplayName("주문 → returnUrl 검증·승인 → 부분취소 2회 → 전액취소 - 취소 회차마다 새 orderId")
	void approveAndCancel() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();
		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "paid", 12000, 12000)));

		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(NICEPAY.request(before).path()).isEqualTo("/v1/payments/" + TID);
		assertThat(NICEPAY.request(before).body()).contains("\"amount\":12000");
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.tid").value(TID));

		// 새로고침 등 재진입 - 결제가 성립한 주문은 승인 호출 없이 결과 화면
		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(NICEPAY.requestCount()).isEqualTo(before + 1);

		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "partialCancelled", 12000, 7000)));
		mockMvc.perform(cancelPost(orderId, 5000))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmt").value(7000));
		assertThat(NICEPAY.request(before + 1).path()).isEqualTo("/v1/payments/" + TID + "/cancel");
		assertThat(NICEPAY.request(before + 1).body()).contains("\"orderId\":\"" + orderId + "-c1\"", "\"cancelAmt\":5000");

		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "partialCancelled", 12000, 4000)));
		mockMvc.perform(cancelPost(orderId, 3000))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmt").value(4000));
		assertThat(NICEPAY.request(before + 2).body()).contains("\"orderId\":\"" + orderId + "-c2\"", "\"cancelAmt\":3000");

		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "cancelled", 12000, 0)));
		mockMvc.perform(cancelPost(orderId, null))
			   .andExpect(jsonPath("$.data.status").value("CANCELED"))
			   .andExpect(jsonPath("$.data.cancelCount").value(3));
		assertThat(NICEPAY.request(before + 3).body()).contains("\"orderId\":\"" + orderId + "-c3\"").doesNotContain("cancelAmt");

		// 취소된 주문의 재진입 콜백 - 결제가 성립했던 주문이라 승인 호출 없이 결과 화면(현재 상태 CANCELED 표시)
		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(NICEPAY.requestCount()).isEqualTo(before + 4);
	}

	@Test
	@DisplayName("동시 return 콜백 2건 - 선점한 1건만 승인, PAID·tid 유지, 망취소 없음")
	void concurrentReturn() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();
		// 승인 응답을 늦춰 한 콜백이 승인 중일 때 다른 콜백이 들어오게 한다 — 응답은 1건만 쌓는다(2번째 승인이 가면 500 → 망취소 시도)
		NICEPAY.enqueue(MockResponse.ok(payment(orderId, "paid", 12000, 12000)).delayed(Duration.ofMillis(300)));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(executor.submit(() -> {
				start.await();
				return mockMvc.perform(returnPost(orderId, 12000, authSignature(12000))).andReturn().getResponse().getHeader("Location");
			}));
		}
		start.countDown();
		List<String> locations = new ArrayList<>();
		for (Future<String> future : futures) {
			locations.add(future.get(10, TimeUnit.SECONDS));
		}
		executor.shutdown();

		// 패자는 PROCESSING 또는 PAID 를 보게 되며 둘 다 결과 화면(새로고침하면 확정 상태)
		assertThat(locations).containsOnly("/result.html?orderId=" + orderId);
		assertThat(NICEPAY.requestCount() - before).isEqualTo(1);
		assertThat(NICEPAY.request(before).path()).isEqualTo("/v1/payments/" + TID);
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.tid").value(TID));
	}

	@Test
	@DisplayName("취소 거절이면 원래 상태 복원, 결과불명이면 CANCEL_UNKNOWN - 재취소 400")
	void cancelRejectedThenUnknown() throws Exception {
		String orderId = paidOrder(12000);
		int before = NICEPAY.requestCount();

		NICEPAY.enqueue(MockResponse.ok("{\"resultCode\":\"2001\",\"resultMsg\":\"취소 불가 거래입니다.\"}"));
		mockMvc.perform(cancelPost(orderId, null))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("NICEPAY_2001"));
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.cancelCount").value(1));

		// 거절된 회차의 orderId 도 다시 쓰지 않는다
		NICEPAY.enqueue(MockResponse.status(500, "{}"));
		mockMvc.perform(cancelPost(orderId, null))
			   .andExpect(status().isBadGateway())
			   .andExpect(jsonPath("$.code").value("NICEPAY-UNKNOWN"));
		assertThat(NICEPAY.request(before + 1).body()).contains("\"orderId\":\"" + orderId + "-c2\"");
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("CANCEL_UNKNOWN"))
			   .andExpect(jsonPath("$.data.tid").value(TID));

		mockMvc.perform(cancelPost(orderId, null))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("C400"));
		// 결과불명 주문의 재진입 콜백 - 승인 호출 없이 결과 화면
		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", "/result.html?orderId=" + orderId));
		assertThat(NICEPAY.requestCount()).isEqualTo(before + 2);
	}

	@Test
	@DisplayName("인증결과 서명 위변조 - 승인 호출 없이 실패 화면, 서명 검증 전 입력이라 주문은 READY 유지")
	void tamperedSignature() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();

		mockMvc.perform(returnPost(orderId, 12000, sha256("forged")))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=NICEPAY-SIGNATURE")));
		assertThat(NICEPAY.requestCount()).isEqualTo(before);
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));
	}

	@Test
	@DisplayName("콜백 파라미터 형식 오류·누락 - JSON 400 대신 실패 화면, 주문은 READY")
	void malformedReturn() throws Exception {
		String orderId = createOrder(12000);
		int before = NICEPAY.requestCount();

		mockMvc.perform(post("/nicepay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											   .param("authResultCode", "0000").param("orderId", orderId).param("amount", "abc"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html?code=NICEPAY-AUTH-INVALID")));
		mockMvc.perform(post("/nicepay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											   .param("authResultCode", "0000").param("orderId", orderId))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=NICEPAY-AUTH-INVALID")));
		mockMvc.perform(post("/nicepay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=C404")));

		assertThat(NICEPAY.requestCount()).isEqualTo(before);
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));
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
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));

		// 실패 확정된 주문의 재진입 콜백 - 승인 호출 없이 실패 화면
		mockMvc.perform(returnPost(orderId, 12000, authSignature(12000)))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=C400")));
		assertThat(NICEPAY.requestCount()).isEqualTo(before + 2);
	}

	@Test
	@DisplayName("사용자가 결제창을 닫음(authResultCode != 0000) - 서명 검증 전이라 READY 유지, 실패 화면 문구는 서버 고정 문구")
	void userCanceled() throws Exception {
		String orderId = createOrder(12000);

		String location = mockMvc.perform(post("/nicepay/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
																 .param("authResultCode", "9999").param("authResultMsg", "사용자 취소")
																 .param("orderId", orderId).param("clientId", CLIENT_KEY))
								 .andReturn().getResponse().getHeader("Location");
		assertThat(location).startsWith("/fail.html?code=NICEPAY_9999");
		assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8)).contains("결제 인증이 완료되지 않았습니다").doesNotContain("사용자 취소");
		mockMvc.perform(detailGet(orderId))
			   .andExpect(jsonPath("$.data.status").value("READY"));
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
