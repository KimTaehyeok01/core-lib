// 택배 예제를 실제 앱으로 띄워 접수·조회와 실패 분류별 처리(거절·결과불명 복구·재시도) 검증 (가짜 택배사 서버)
package com.corelib.example.webclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.time.Duration;
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
	@DisplayName("택배사 거절(4xx) - 오류코드를 COURIER_{code} 로 그대로, 상태 FAILED")
	void rejected() throws Exception {
		COURIER.enqueue(MockResponse.status(409, "{\"code\":\"DUPLICATE_ORDER\",\"message\":\"이미 접수된 주문번호입니다.\"}"));

		register("ORD-2")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("COURIER_DUPLICATE_ORDER"))
			.andExpect(jsonPath("$.message").value("이미 접수된 주문번호입니다."));
		mockMvc.perform(get("/api/shipments/detail").param("orderNo", "ORD-2"))
			   .andExpect(jsonPath("$.data.status").value("FAILED"));
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
