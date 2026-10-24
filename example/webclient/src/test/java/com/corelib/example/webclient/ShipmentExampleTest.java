package com.corelib.example.webclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ShipmentExampleTest {

	static final MockHttpServer COURIER = MockHttpServer.start();

	private final MockMvc mockMvc;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("example.courier.base-url", COURIER::baseUrl);
		registry.add("example.courier.api-key", () -> "courier-test-key");
		registry.add("example.courier.read-timeout", () -> "500ms");
	}

	@AfterAll
	static void closeServer() {
		COURIER.close();
	}

	private ResultActions register(String orderNo) throws Exception {
		return mockMvc.perform(post("/api/shipments/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"orderNo\":\"" + orderNo + "\",\"receiverName\":\"홍길동\",\"receiverMobileNo\":\"010-1234-5678\",\"address\":\"서울시 송파구\"}"));
	}

	private static String shipment(String orderNo, String invoiceNo, String status) {
		return "{\"orderNo\":\"" + orderNo + "\",\"invoiceNo\":\"" + invoiceNo + "\",\"status\":\"" + status + "\","
			+ "\"events\":[{\"occurredAt\":\"2026-10-18 10:00\",\"location\":\"송파 집하점\",\"description\":\"집하 완료\"}]}";
	}

	@Test
	@DisplayName("접수 성공 → 택배사에 인증 헤더·정리된 번호 전송, 응답은 마스킹 → 재요청은 재전송 없이 그대로 → 추적")
	void registerAndTrack() throws Exception {
		int before = COURIER.requestCount();
		COURIER.enqueue(MockResponse.ok(shipment("ORD-1", "INV0000000001", "ACCEPTED")));

		register("ORD-1")
			.andExpect(jsonPath("$.data.status").value("REGISTERED"))
			.andExpect(jsonPath("$.data.invoiceNo").value("INV0000000001"))
			.andExpect(jsonPath("$.data.maskedReceiverName").value("홍*동"))
			.andExpect(jsonPath("$.data.maskedReceiverMobileNo").value("010-****-5678"));
		assertThat(COURIER.request(before).path()).isEqualTo("/v1/shipments");
		assertThat(COURIER.request(before).header("Authorization")).isEqualTo("Bearer courier-test-key");
		assertThat(COURIER.request(before).body()).contains("\"receiverMobileNo\":\"01012345678\"");

		register("ORD-1").andExpect(jsonPath("$.data.status").value("REGISTERED"));
		assertThat(COURIER.requestCount()).isEqualTo(before + 1);

		COURIER.enqueue(MockResponse.ok(shipment("ORD-1", "INV0000000001", "IN_TRANSIT")));
		mockMvc.perform(get("/api/shipments/track").param("orderNo", "ORD-1"))
			   .andExpect(jsonPath("$.data.deliveryStatus").value("IN_TRANSIT"))
			   .andExpect(jsonPath("$.data.events[0].location").value("송파 집하점"));
		assertThat(COURIER.request(before + 1).path()).isEqualTo("/v1/shipments/track?invoiceNo=INV0000000001");
	}

	@Test
	@DisplayName("택배사 거절(422 등 4xx) - COURIER_{code}·400, 메시지는 고정 문구(택배사 원문은 로그에만), 상태 FAILED")
	void rejected() throws Exception {
		COURIER.enqueue(MockResponse.status(422, "{\"code\":\"INVALID_ADDRESS\",\"message\":\"주소 검증 실패: courier-internal-detail\"}"));

		register("ORD-2")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COURIER_INVALID_ADDRESS"))
			.andExpect(jsonPath("$.message").value("택배사가 요청을 거절했습니다. 입력값을 확인해 주세요."))
			.andExpect(content().string(not(containsString("courier-internal-detail"))));
		mockMvc.perform(get("/api/shipments/detail").param("orderNo", "ORD-2"))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));
	}

	@Test
	@DisplayName("택배사 401 - 우리 API 키 문제라 우리 401 이 아닌 502 COURIER_UNAVAILABLE, 상태 FAILED(키 고친 뒤 재접수 가능)")
	void courierUnauthorized() throws Exception {
		COURIER.enqueue(MockResponse.status(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid key: courier-internal-detail\"}"));

		register("ORD-21")
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("COURIER_UNAVAILABLE"))
			.andExpect(content().string(not(containsString("courier-internal-detail"))));
		mockMvc.perform(get("/api/shipments/detail").param("orderNo", "ORD-21"))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));
	}

	@Test
	@DisplayName("택배사 409 DUPLICATE_ORDER - FAILED 가 아니라 주문번호 조회(reconcile)로 확인해 REGISTERED")
	void duplicateOrderReconciled() throws Exception {
		int before = COURIER.requestCount();
		COURIER.enqueue(MockResponse.status(409, "{\"code\":\"DUPLICATE_ORDER\",\"message\":\"이미 접수된 주문번호입니다.\"}"));
		COURIER.enqueue(MockResponse.ok(shipment("ORD-22", "INV0000000022", "ACCEPTED")));

		register("ORD-22")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REGISTERED"))
			.andExpect(jsonPath("$.data.invoiceNo").value("INV0000000022"));
		assertThat(COURIER.request(before + 1).path()).isEqualTo("/v1/shipments/by-order?orderNo=ORD-22");
	}

	@Test
	@DisplayName("같은 주문 동시 접수 2건 - PENDING 을 선점한 1건만 택배사 POST, 둘 다 200, 최종 REGISTERED 유지")
	void concurrentRegister() throws Exception {
		int before = COURIER.requestCount();
		// 접수 응답을 늦춰 첫 요청이 택배사 호출 중일 때 두 번째 요청이 들어오게 한다
		COURIER.enqueue(MockResponse.ok(shipment("ORD-23", "INV0000000023", "ACCEPTED")).delayed(Duration.ofMillis(300)));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return register("ORD-23").andReturn().getResponse().getStatus();
				}));
			}
			ready.await(5, TimeUnit.SECONDS);
			start.countDown();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(10, TimeUnit.SECONDS));
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(statuses).containsOnly(200);
		assertThat(COURIER.requestCount()).isEqualTo(before + 1);
		mockMvc.perform(get("/api/shipments/detail").param("orderNo", "ORD-23"))
			   .andExpect(jsonPath("$.data.status").value("REGISTERED"))
			   .andExpect(jsonPath("$.data.invoiceNo").value("INV0000000023"));
	}

	@Test
	@DisplayName("접수·주문번호 조회 모두 200 빈 본문 - NPE(500) 대신 502 COURIER_UNKNOWN, CHECK_REQUIRED 로 재접수 차단")
	void emptyBodyOnRegister() throws Exception {
		COURIER.enqueue(MockResponse.ok(""));
		COURIER.enqueue(MockResponse.ok(""));

		register("ORD-24")
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("COURIER_UNKNOWN"));
		mockMvc.perform(get("/api/shipments/detail").param("orderNo", "ORD-24"))
			   .andExpect(jsonPath("$.data.status").value("CHECK_REQUIRED"));
	}

	@Test
	@DisplayName("추적 200 빈 본문 - NPE(500) 대신 502 COURIER_UNKNOWN")
	void emptyBodyOnTrack() throws Exception {
		COURIER.enqueue(MockResponse.ok(shipment("ORD-25", "INV0000000025", "ACCEPTED")));
		register("ORD-25").andExpect(jsonPath("$.data.status").value("REGISTERED"));

		COURIER.enqueue(MockResponse.ok(""));
		mockMvc.perform(get("/api/shipments/track").param("orderNo", "ORD-25"))
			   .andExpect(status().isBadGateway())
			   .andExpect(jsonPath("$.code").value("COURIER_UNKNOWN"));
	}

	@Test
	@DisplayName("접수 응답 timeout(결과불명) - 재전송하지 않고 주문번호 조회로 접수 확인")
	void unknownThenReconciled() throws Exception {
		int before = COURIER.requestCount();
		COURIER.enqueue(MockResponse.ok(shipment("ORD-3", "INV0000000003", "ACCEPTED")).delayed(Duration.ofSeconds(1)));
		COURIER.enqueue(MockResponse.ok(shipment("ORD-3", "INV0000000003", "ACCEPTED")));

		register("ORD-3")
			.andExpect(jsonPath("$.data.status").value("REGISTERED"))
			.andExpect(jsonPath("$.data.invoiceNo").value("INV0000000003"));
		assertThat(COURIER.request(before + 1).path()).isEqualTo("/v1/shipments/by-order?orderNo=ORD-3");
	}

	@Test
	@DisplayName("결과불명 + 조회도 5xx 두 번 - CHECK_REQUIRED, 이후 접수 요청은 택배사로 보내지 않음")
	void unknownAndUnresolved() throws Exception {
		COURIER.enqueue(MockResponse.status(503, "{}"));
		COURIER.enqueue(MockResponse.status(503, "{}"));
		COURIER.enqueue(MockResponse.status(503, "{}"));

		register("ORD-4")
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("COURIER_UNKNOWN"));
		int after = COURIER.requestCount();
		register("ORD-4").andExpect(jsonPath("$.data.status").value("CHECK_REQUIRED"));
		assertThat(COURIER.requestCount()).isEqualTo(after);
	}

	@Test
	@DisplayName("결과불명 + 조회 결과 없음(404) - 접수 안 됨 확정, 다시 접수하면 성공")
	void unknownButNotRegistered() throws Exception {
		COURIER.enqueue(MockResponse.status(500, "{}"));
		COURIER.enqueue(MockResponse.status(404, "{\"code\":\"NOT_FOUND\",\"message\":\"접수 내역이 없습니다.\"}"));
		register("ORD-5").andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("COURIER_UNAVAILABLE"));
		mockMvc.perform(get("/api/shipments/detail").param("orderNo", "ORD-5"))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));

		COURIER.enqueue(MockResponse.ok(shipment("ORD-5", "INV0000000005", "ACCEPTED")));
		register("ORD-5").andExpect(jsonPath("$.data.status").value("REGISTERED"));
	}

	@Test
	@DisplayName("추적 GET 이 5xx 면 1회 재시도해서 성공")
	void trackRetry() throws Exception {
		COURIER.enqueue(MockResponse.ok(shipment("ORD-6", "INV0000000006", "ACCEPTED")));
		register("ORD-6").andExpect(jsonPath("$.data.status").value("REGISTERED"));

		COURIER.enqueue(MockResponse.status(502, "{}"));
		COURIER.enqueue(MockResponse.ok(shipment("ORD-6", "INV0000000006", "DELIVERED")));
		mockMvc.perform(get("/api/shipments/track").param("orderNo", "ORD-6"))
			   .andExpect(jsonPath("$.data.deliveryStatus").value("DELIVERED"));
	}
}
