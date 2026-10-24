package com.corelib.example.payment.kspay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class KsPayExampleTest {

	static final MockHttpServer KSPAY = MockHttpServer.start();
	private static final Charset MS949 = Charset.forName("x-windows-949");
	private static final String MID = "2999199999";
	private static final String CANCEL_OK = "{\"code\":\"A0200\",\"data\":{\"respCode\":\"0000\",\"respMessage\":\"승인취소완료\"}}";

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("payment.kspay.mid", () -> MID);
		registry.add("payment.kspay.api-key", () -> "pgapi-test-key");
		registry.add("payment.kspay.approval-base-url", KSPAY::baseUrl);
		registry.add("payment.kspay.webfep-base-url", KSPAY::baseUrl);
		registry.add("payment.kspay.read-timeout", () -> "1s");
	}

	@AfterAll
	static void closeServer() {
		KSPAY.close();
	}

	private String createOrder(int amount) throws Exception {
		String body = mockMvc.perform(post("/api/kspay/orders/create").contentType(MediaType.APPLICATION_JSON)
																	  .content("{\"goodsName\":\"에코백\",\"amount\":" + amount + "}"))
							 .andExpect(jsonPath("$.data.storeId").value(MID))
							 .andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.orderNo");
	}

	/** 통합모듈 승인 응답 재현 — 첫 토큰(버림) + sndRpyParams 순서 값 + 꼬리, MS949 */
	private static MockResponse approval(String orderNo, int amount, String result) {
		String body = String.join("`", "", "O", "T0000001", "20261018", "101530", String.valueOf(amount), "30012345", "정상승인", "",
			"04", "04", result, "0000", "00", "377649XXXXXX101", MID, orderNo, "꼬리", "");
		return MockResponse.bytes(200, body.getBytes(MS949), "text/html;charset=euc-kr");
	}

	/** 카드 승인까지 마친 주문 */
	private String paidOrder(int amount, String payKey) throws Exception {
		String orderNo = createOrder(amount);
		KSPAY.enqueue(approval(orderNo, amount, "1001"));
		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", payKey))
			   .andExpect(header().string("Location", "/result.html?orderNo=" + orderNo));
		return orderNo;
	}

	/** 같은 요청을 count 건 동시에 보낸다 — 모든 스레드가 준비된 뒤 한 번에 출발시켜 실제로 겹치게 한다 */
	private static List<MvcResult> concurrently(int count, Callable<MvcResult> request) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch ready = new CountDownLatch(count);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return request.call();
				}));
			}
			ready.await();
			start.countDown();
			List<MvcResult> results = new ArrayList<>();
			for (Future<MvcResult> future : futures) {
				results.add(future.get(10, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("결제창 결과 → 승인(form 의 sndAmount 무시, 서버 금액 사용) → 부분취소 → 잔액 취소")
	void approveAndCancel() throws Exception {
		String orderNo = createOrder(9000);
		int before = KSPAY.requestCount();
		KSPAY.enqueue(approval(orderNo, 9000, "1001"));

		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("sndAmount", "100").param("reCommConId", "pay-key-1"))
			   .andExpect(header().string("Location", "/result.html?orderNo=" + orderNo));
		assertThat(KSPAY.request(before).path()).isEqualTo("/store/KSPayWebV1.4/web_host/recv_post.jsp");
		assertThat(KSPAY.request(before).body()).contains("sndCommConId=pay-key-1", "sndAmount=9000");
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.payMethod").value("CARD"))
			   .andExpect(jsonPath("$.data.tid").value("T0000001"));

		KSPAY.enqueue(MockResponse.ok(CANCEL_OK));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\",\"cancelAmount\":3000}"))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(6000));
		assertThat(KSPAY.request(before + 1).path()).isEqualTo("/kspay/webfep/api/v1/card/cancel");
		assertThat(KSPAY.request(before + 1).header("Authorization")).isEqualTo("pgapi pgapi-test-key");
		assertThat(KSPAY.request(before + 1).body()).contains("\"orgTradeKey\":\"T0000001\"");

		KSPAY.enqueue(MockResponse.ok(CANCEL_OK));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\"}"))
			   .andExpect(jsonPath("$.data.status").value("CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(0))
			   .andExpect(jsonPath("$.data.partialCancelCount").value(2));
	}

	@Test
	@DisplayName("승인 금액이 주문과 다름 - 승인 성립 상태라 UNKNOWN(관리자 확인)")
	void amountMismatch() throws Exception {
		String orderNo = createOrder(9000);
		KSPAY.enqueue(approval(orderNo, 100, "1001"));

		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-2"))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=KSPAY-AMOUNT")));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("UNKNOWN"));

		// 다시 온 콜백 — 승인을 또 보내지 않는다. UNKNOWN 은 승인이 성립했을 수 있어 결과 화면(관리자 확인 사유 표시)
		int before = KSPAY.requestCount();
		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-2"))
			   .andExpect(header().string("Location", "/result.html?orderNo=" + orderNo));
		assertThat(KSPAY.requestCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("승인 콜백 동시 2건 - 한 건만 선점해 승인 1회, PAID·TID 유지")
	void concurrentApprove() throws Exception {
		String orderNo = createOrder(9000);
		int before = KSPAY.requestCount();
		// 승인 응답을 늦춰 두 요청을 겹치게 한다 — 진 요청이 승인을 또 보내면 응답이 없어 결과불명으로 PAID 를 덮는다
		KSPAY.enqueue(approval(orderNo, 9000, "1001").delayed(Duration.ofMillis(500)));

		List<MvcResult> results = concurrently(2, () -> mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
																							 .param("sndOrdernumber", orderNo)
																							 .param("reCommConId", "pay-key-c1"))
																   .andReturn());

		assertThat(KSPAY.requestCount() - before).isEqualTo(1);
		// 이긴 요청은 PAID, 진 요청은 처리 중(PROCESSING) — 둘 다 결과 화면에서 현재 상태를 본다
		assertThat(results).extracting(result -> result.getResponse().getHeader("Location"))
						   .containsOnly("/result.html?orderNo=" + orderNo);
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.tid").value("T0000001"));

		// 처리된 주문에 다시 온 콜백 — 승인 없이 결과 화면
		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-c1"))
			   .andExpect(header().string("Location", "/result.html?orderNo=" + orderNo));
		assertThat(KSPAY.requestCount() - before).isEqualTo(1);
	}

	@Test
	@DisplayName("부분취소 동시 2건 - 한 건만 선점(다른 건 400), 잔액·회차는 선점한 최신 상태 기준")
	void concurrentPartialCancel() throws Exception {
		String orderNo = paidOrder(9000, "pay-key-c2");
		int before = KSPAY.requestCount();
		KSPAY.enqueue(MockResponse.ok(CANCEL_OK).delayed(Duration.ofMillis(500)));

		List<MvcResult> results = concurrently(2, () -> mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
																										.content("{\"orderNo\":\"" + orderNo + "\",\"cancelAmount\":3000}"))
																   .andReturn());

		assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 400);
		assertThat(KSPAY.requestCount() - before).isEqualTo(1);
		assertThat(KSPAY.request(before).body()).contains("\"cancelTotalAmount\":\"3000\"", "\"cancelSeq\":\"1\"");
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("PARTIAL_CANCELED"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(6000))
			   .andExpect(jsonPath("$.data.partialCancelCount").value(1));

		// 다음 부분취소는 갱신된 잔액·회차로 나간다
		KSPAY.enqueue(MockResponse.ok(CANCEL_OK));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\",\"cancelAmount\":3000}"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(3000))
			   .andExpect(jsonPath("$.data.partialCancelCount").value(2));
		assertThat(KSPAY.request(before + 1).body()).contains("\"cancelSeq\":\"2\"");
	}

	@Test
	@DisplayName("승인 응답의 결제수단 코드 미지원 - PAID 대신 UNKNOWN(관리자 확인), 취소 불가")
	void unsupportedPayMethod() throws Exception {
		String orderNo = createOrder(9000);
		KSPAY.enqueue(approval(orderNo, 9000, "9001"));

		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-u1"))
			   .andExpect(header().string("Location", "/result.html?orderNo=" + orderNo));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
			   .andExpect(jsonPath("$.data.tid").value("T0000001"));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\"}"))
			   .andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("승인 거절 - FAILED, 다시 온 콜백도 승인 없이 실패 화면")
	void rejectedThenReentry() throws Exception {
		String orderNo = createOrder(9000);
		String rejected = String.join("`", "", "X", "", "20261018", "101530", "9000", "", "한도초과", "", "", "",
			"1001", "7003", "", "", MID, orderNo, "꼬리", "");
		KSPAY.enqueue(MockResponse.bytes(200, rejected.getBytes(MS949), "text/html;charset=euc-kr"));

		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-r1"))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=KSPAY_7003")));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));

		int before = KSPAY.requestCount();
		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-r1"))
			   .andExpect(header().string("Location", startsWith("/fail.html?code=FAILED")));
		assertThat(KSPAY.requestCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("취소 거절은 원래 상태 복원, 취소 결과불명(timeout)은 CANCEL_UNKNOWN 으로 남기고 재취소 차단")
	void cancelFailure() throws Exception {
		String orderNo = paidOrder(9000, "pay-key-f1");
		int before = KSPAY.requestCount();

		KSPAY.enqueue(MockResponse.ok("{\"code\":\"A0200\",\"data\":{\"respCode\":\"7001\",\"respMessage\":\"취소 불가 거래\"}}"));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\",\"cancelAmount\":3000}"))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("KSPAY_7001"));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.balanceAmount").value(9000));

		// 응답 지연이 read-timeout(1s)을 넘으면 결과불명
		KSPAY.enqueue(MockResponse.ok(CANCEL_OK).delayed(Duration.ofSeconds(2)));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\"}"))
			   .andExpect(jsonPath("$.code").value("KSPAY-UNKNOWN"));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("CANCEL_UNKNOWN"));

		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\"}"))
			   .andExpect(status().isBadRequest());
		assertThat(KSPAY.requestCount() - before).isEqualTo(2);
	}

	@Test
	@DisplayName("사용자 취소·결제키 누락은 READY 유지(재결제 가능), 콜백 파라미터 누락은 실패 화면")
	void browserInputKeepsReady() throws Exception {
		String orderNo = createOrder(9000);
		mockMvc.perform(post("/api/kspay/orders/user-cancel").contentType(MediaType.APPLICATION_JSON)
															 .content("{\"orderNo\":\"" + orderNo + "\"}"));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("READY"))
			   .andExpect(jsonPath("$.data.failMessage").value("사용자가 결제를 취소했습니다."));

		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("reCommConId", "pay-key-b1"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html?code=C404")));
		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("sndOrdernumber", orderNo))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html?code=C400")));
		mockMvc.perform(post("/kspay/rcv").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("reCommType", "1"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", startsWith("/fail.html?code=C400")));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("READY"));

		// 같은 주문으로 다시 결제 — 이전 사유는 지운다
		KSPAY.enqueue(approval(orderNo, 9000, "1001"));
		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-b2"))
			   .andExpect(header().string("Location", "/result.html?orderNo=" + orderNo));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("PAID"))
			   .andExpect(jsonPath("$.data.failMessage").doesNotExist());
	}

	@Test
	@DisplayName("가상계좌 발급 - 입금 대기, 전액 회수(recall)")
	void virtualAccount() throws Exception {
		String orderNo = createOrder(9000);
		int before = KSPAY.requestCount();
		KSPAY.enqueue(approval(orderNo, 9000, "6001"));

		mockMvc.perform(post("/kspay/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
											 .param("sndOrdernumber", orderNo).param("reCommConId", "pay-key-3"));
		mockMvc.perform(get("/api/kspay/orders/detail").param("orderNo", orderNo))
			   .andExpect(jsonPath("$.data.status").value("WAITING_FOR_DEPOSIT"));

		KSPAY.enqueue(MockResponse.ok(CANCEL_OK));
		mockMvc.perform(post("/api/kspay/orders/cancel").contentType(MediaType.APPLICATION_JSON)
														.content("{\"orderNo\":\"" + orderNo + "\"}"))
			   .andExpect(jsonPath("$.data.status").value("CANCELED"));
		assertThat(KSPAY.request(before + 1).path()).isEqualTo("/kspay/webfep/api/v1/vaccount/recall");
	}

	@Test
	@DisplayName("sndReply 수신 페이지 - 결제키를 이스케이프해 opener 로 넘기고, 취소면 mcancel")
	void receivePage() throws Exception {
		mockMvc.perform(post("/kspay/rcv").contentType(MediaType.APPLICATION_FORM_URLENCODED)
										  .param("reCommConId", "\"><script>alert(1)</script>").param("reCommType", "1").param("reHash", "h"))
			   .andExpect(content().string(containsString("data-rcid=\"&quot;&gt;&lt;script&gt;")))
			   .andExpect(content().string(not(containsString("<script>alert(1)"))))
			   .andExpect(content().string(containsString("data-canceled=\"false\"")));

		mockMvc.perform(post("/kspay/rcv").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("reCnclType", "1"))
			   .andExpect(content().string(containsString("data-canceled=\"true\"")));
	}

	@Test
	@DisplayName("기동 안내 - KsPayUtils 보고")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("KsPayUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isEqualTo(true);
		assertThat(report.getDetail()).contains("mid=2999199999", "취소 가능");
	}
}
