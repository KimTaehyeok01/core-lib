// TossPayUtils 승인/취소/재조회 분기 검증 (로컬 HTTP 서버로 토스 응답 재현 — timeout·연결 거부 포함)
package com.corelib.payment.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.webclient.WebClientUtils;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TossPayUtilsTest {

	private MockHttpServer server;

	@BeforeEach
	void setUp() {
		server = MockHttpServer.start();
		TossPayUtils.initialize(WebClientUtils.builder(server.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500)).build());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private static TossPayDto.ConfirmRequest request() {
		return TossPayDto.ConfirmRequest.builder().paymentKey("pk_1").orderId("order-1").amount(10000).build();
	}

	private static String payment(String status, int amount) {
		return "{\"paymentKey\":\"pk_1\",\"orderId\":\"order-1\",\"status\":\"" + status + "\",\"totalAmount\":" + amount
			+ ",\"balanceAmount\":" + amount + "}";
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@Test
	@DisplayName("승인 성공 - POST /v1/payments/confirm, 금액/주문번호 일치, DONE")
	void confirm_success() {
		server.enqueue(MockResponse.ok(payment("DONE", 10000)));

		TossPayDto.Payment result = TossPayUtils.confirm(request());

		assertThat(result.isDone()).isTrue();
		assertThat(server.request(0).method()).isEqualTo("POST");
		assertThat(server.request(0).path()).isEqualTo("/v1/payments/confirm");
		assertThat(server.request(0).body()).contains("\"paymentKey\":\"pk_1\"", "\"orderId\":\"order-1\"", "\"amount\":10000");
	}

	@Test
	@DisplayName("가상계좌 - WAITING_FOR_DEPOSIT 은 발급 성공으로 반환(입금 전)")
	void confirm_virtualAccount() {
		server.enqueue(MockResponse.ok(payment("WAITING_FOR_DEPOSIT", 10000)));

		TossPayDto.Payment result = TossPayUtils.confirm(request());

		assertThat(result.isWaitingForDeposit()).isTrue();
		assertThat(result.isDone()).isFalse();
	}

	@Test
	@DisplayName("승인 금액 불일치 - AMOUNT_MISMATCH (승인 성립, 취소 필요)")
	void confirm_amountMismatch() {
		server.enqueue(MockResponse.ok(payment("DONE", 9999)));

		assertThatThrownBy(() -> TossPayUtils.confirm(request()))
			.extracting(TossPayUtilsTest::errorCodeOf)
			.isEqualTo(TossPayError.AMOUNT_MISMATCH);
	}

	@Test
	@DisplayName("토스 거절(4xx) - TOSS_{code}, 메시지는 토스 원문")
	void confirm_vendorRejection() {
		server.enqueue(MockResponse.status(400, "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족\"}"));

		assertThatThrownBy(() -> TossPayUtils.confirm(request()))
			.hasMessage("한도초과 혹은 잔액부족")
			.satisfies(e -> {
				assertThat(errorCodeOf(e).code()).isEqualTo("TOSS_REJECT_CARD_PAYMENT");
				assertThat(errorCodeOf(e).status()).isEqualTo(400);
			});
	}

	@Test
	@DisplayName("결과불명(5xx) → 재조회 DONE - 승인으로 확정")
	void confirm_serverError_recheckApproved() {
		server.enqueue(MockResponse.status(500, "{}"));
		server.enqueue(MockResponse.ok(payment("DONE", 10000)));

		assertThat(TossPayUtils.confirm(request()).isDone()).isTrue();
		assertThat(server.request(1).method()).isEqualTo("GET");
		assertThat(server.request(1).path()).isEqualTo("/v1/payments/pk_1");
	}

	@Test
	@DisplayName("결과불명(응답 timeout) → 재조회 DONE - 승인으로 확정")
	void confirm_readTimeout_recheckApproved() {
		server.enqueue(MockResponse.ok(payment("DONE", 10000)).delayed(Duration.ofSeconds(3)));
		server.enqueue(MockResponse.ok(payment("DONE", 10000)));

		assertThat(TossPayUtils.confirm(request()).isDone()).isTrue();
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("결과불명 → 재조회 ABORTED - NOT_APPROVED (미성립 확정)")
	void confirm_serverError_recheckAborted() {
		server.enqueue(MockResponse.status(500, "{}"));
		server.enqueue(MockResponse.ok(payment("ABORTED", 10000)));

		assertThatThrownBy(() -> TossPayUtils.confirm(request()))
			.extracting(TossPayUtilsTest::errorCodeOf)
			.isEqualTo(TossPayError.NOT_APPROVED);
	}

	@Test
	@DisplayName("결과불명 → 재조회도 실패 - RESULT_UNKNOWN")
	void confirm_serverError_recheckFails() {
		server.enqueue(MockResponse.status(500, "{}"));
		server.enqueue(MockResponse.status(503, "{}"));

		assertThatThrownBy(() -> TossPayUtils.confirm(request()))
			.extracting(TossPayUtilsTest::errorCodeOf)
			.isEqualTo(TossPayError.RESULT_UNKNOWN);
	}

	@Test
	@DisplayName("연결 거부 - 요청 미전송이라 결과확정(EXTERNAL_ERROR), 재조회 안 함")
	void confirm_connectionRefused() {
		TossPayUtils.initialize(WebClientUtils.builder(MockHttpServer.closedPortUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1)).build());

		assertThatThrownBy(() -> TossPayUtils.confirm(request()))
			.extracting(TossPayUtilsTest::errorCodeOf)
			.isEqualTo(TossPayError.EXTERNAL_ERROR);
	}

	@Test
	@DisplayName("요청 검증 실패 - BAD_REQUEST (토스 호출 전 차단)")
	void confirm_validation() {
		TossPayDto.ConfirmRequest invalid = TossPayDto.ConfirmRequest.builder().paymentKey("pk_1").orderId("order-1").build();

		assertThatThrownBy(() -> TossPayUtils.confirm(invalid))
			.extracting(TossPayUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(server.requestCount()).isZero();
	}

	@Test
	@DisplayName("취소 성공 - 전액취소, balanceAmount 0, cancelAmount 미전송")
	void cancel_success() {
		server.enqueue(MockResponse.ok(
			"{\"paymentKey\":\"pk_1\",\"orderId\":\"order-1\",\"status\":\"CANCELED\",\"totalAmount\":10000,\"balanceAmount\":0}"));

		TossPayDto.Payment result = TossPayUtils.cancel("pk_1", TossPayDto.CancelRequest.builder().cancelReason("고객 변심").build());

		assertThat(result.getPaymentStatus()).isEqualTo(TossPayDto.Status.CANCELED);
		assertThat(result.getBalanceAmount()).isZero();
		assertThat(server.request(0).path()).isEqualTo("/v1/payments/pk_1/cancel");
		assertThat(server.request(0).body()).contains("\"cancelReason\":\"고객 변심\"").doesNotContain("cancelAmount");
	}

	@Test
	@DisplayName("취소 결과불명 - 재시도 없이 RESULT_UNKNOWN (요청은 정확히 1번)")
	void cancel_serverError_noRetry() {
		server.enqueue(MockResponse.status(500, "{}"));

		assertThatThrownBy(() -> TossPayUtils.cancel("pk_1", TossPayDto.CancelRequest.builder().cancelReason("고객 변심").build()))
			.extracting(TossPayUtilsTest::errorCodeOf)
			.isEqualTo(TossPayError.RESULT_UNKNOWN);
		assertThat(server.requestCount()).isEqualTo(1);
	}
}
