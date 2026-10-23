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

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.util.List;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.Charset;
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
