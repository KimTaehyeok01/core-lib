package com.corelib.payment.nice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.webclient.WebClientUtils;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NicePayUtilsTest {

	private static final String CLIENT_KEY = "S2_test";
	private static final String SIGN_KEY = "sk-test";
	private static final String TID = "tid_1";
	private static final String EDI_DATE = "2026-10-18T10:00:00.000+0900";

	private MockHttpServer server;

	@BeforeEach
	void setUp() {
		server = MockHttpServer.start();
		NicePayProperty property = new NicePayProperty();
		property.setClientKey(CLIENT_KEY);
		property.setSecretKey(SIGN_KEY);
		NicePayUtils.initialize(WebClientUtils.builder(server.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500)).build(), property);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	/** 구현과 독립적으로 매뉴얼 규칙대로 서명을 만든다 */
	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private static NicePayDto.AuthResult authResult(String code, Integer amount, String signature) {
		NicePayDto.AuthResult result = new NicePayDto.AuthResult();
		result.setAuthResultCode(code);
		result.setAuthResultMsg("0000".equals(code) ? "인증 성공" : "사용자 취소");
		result.setTid(TID);
		result.setClientId(CLIENT_KEY);
		result.setOrderId("order-1");
		result.setAmount(amount);
		result.setAuthToken("auth-token");
		result.setSignature(signature);
		return result;
	}

	private static String payment(String resultCode, String status, int amount, String payMethod) throws Exception {
		return "{\"resultCode\":\"" + resultCode + "\",\"resultMsg\":\"정상 처리되었습니다.\",\"tid\":\"" + TID
			+ "\",\"orderId\":\"order-1\",\"ediDate\":\"" + EDI_DATE + "\",\"signature\":\""
			+ sha256(TID + amount + EDI_DATE + SIGN_KEY) + "\",\"status\":\"" + status + "\",\"payMethod\":\"" + payMethod
			+ "\",\"amount\":" + amount + ",\"balanceAmt\":" + amount + "}";
	}

	private static NicePayDto.ApproveRequest approveRequest() {
		return NicePayDto.ApproveRequest.builder().tid(TID).orderId("order-1").amount(10000).build();
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	// === 인증결과 검증 (네트워크 없음) ===

	@Test
	@DisplayName("인증결과 정상 - 서명 일치 시 tid 반환")
	void verifyAuthResult_success() throws Exception {
		String signature = sha256("auth-token" + CLIENT_KEY + 10000 + SIGN_KEY);

		assertThat(NicePayUtils.verifyAuthResult(authResult("0000", 10000, signature), "order-1", 10000)).isEqualTo(TID);
	}

	@Test
	@DisplayName("인증 실패(사용자 취소 등) - NICEPAY_{authResultCode} 패스스루")
	void verifyAuthResult_authFailed() {
		assertThatThrownBy(() -> NicePayUtils.verifyAuthResult(authResult("9100", 10000, "x"), "order-1", 10000))
			.hasMessage("사용자 취소")
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("NICEPAY_9100");
	}

	@Test
	@DisplayName("브라우저에서 금액 조작 - 서명이 맞아도 기대 금액과 다르면 AUTH_MISMATCH")
	void verifyAuthResult_amountTampered() throws Exception {
		String signature = sha256("auth-token" + CLIENT_KEY + 100 + SIGN_KEY);

		assertThatThrownBy(() -> NicePayUtils.verifyAuthResult(authResult("0000", 100, signature), "order-1", 10000))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.AUTH_MISMATCH);
	}

	@Test
	@DisplayName("서명 위조 - SIGNATURE_MISMATCH")
	void verifyAuthResult_forgedSignature() throws Exception {
		String forged = sha256("auth-token" + CLIENT_KEY + 10000 + "other-key");

		assertThatThrownBy(() -> NicePayUtils.verifyAuthResult(authResult("0000", 10000, forged), "order-1", 10000))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.SIGNATURE_MISMATCH);
	}

	// === 승인 ===

	@Test
	@DisplayName("승인 성공 - 본문은 amount 만, charset=utf-8, 응답 서명·금액·상태 검증 통과")
	void approve_success() throws Exception {
		server.enqueue(MockResponse.ok(payment("0000", "paid", 10000, "card")));

		NicePayDto.Payment result = NicePayUtils.approve(approveRequest());

		assertThat(result.isPaid()).isTrue();
		assertThat(server.request(0).path()).isEqualTo("/v1/payments/" + TID);
		assertThat(server.request(0).body()).isEqualTo("{\"amount\":10000}");
		assertThat(server.request(0).header("Content-Type")).containsIgnoringCase("charset=utf-8");
	}

	@Test
	@DisplayName("가상계좌 - status ready + vbank 는 발급 성공(입금 전)")
	void approve_vbank() throws Exception {
		server.enqueue(MockResponse.ok(payment("0000", "ready", 10000, "vbank")));

		NicePayDto.Payment result = NicePayUtils.approve(approveRequest());

		assertThat(result.isVbankReady()).isTrue();
		assertThat(result.isPaid()).isFalse();
	}

	@Test
	@DisplayName("승인 금액 불일치 - AMOUNT_MISMATCH (승인 성립, 취소 필요)")
	void approve_amountMismatch() throws Exception {
		server.enqueue(MockResponse.ok(payment("0000", "paid", 9999, "card")));

		assertThatThrownBy(() -> NicePayUtils.approve(approveRequest()))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.AMOUNT_MISMATCH);
	}

	@Test
	@DisplayName("승인 응답 서명 위조 - SIGNATURE_MISMATCH")
	void approve_forgedSignature() throws Exception {
		String tampered = payment("0000", "paid", 10000, "card").replaceFirst("\"signature\":\"[0-9a-f]+\"", "\"signature\":\"deadbeef\"");
		server.enqueue(MockResponse.ok(tampered));

		assertThatThrownBy(() -> NicePayUtils.approve(approveRequest()))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.SIGNATURE_MISMATCH);
	}

	@Test
	@DisplayName("승인 거절(200 + 실패 코드) - NICEPAY_{resultCode}, 메시지는 원문, 망취소 안 함")
	void approve_rejected() {
		server.enqueue(MockResponse.ok("{\"resultCode\":\"3011\",\"resultMsg\":\"카드 한도 초과\",\"tid\":\"" + TID + "\"}"));

		assertThatThrownBy(() -> NicePayUtils.approve(approveRequest()))
			.hasMessage("카드 한도 초과")
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("NICEPAY_3011");
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("승인 응답 timeout → 망취소 성공 - NET_CANCELLED (결제 미성립 확정)")
	void approve_readTimeout_netCancelled() throws Exception {
		server.enqueue(MockResponse.ok(payment("0000", "paid", 10000, "card")).delayed(Duration.ofSeconds(3)));
		server.enqueue(MockResponse.ok(payment("0000", "cancelled", 10000, "card")));

		assertThatThrownBy(() -> NicePayUtils.approve(approveRequest()))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.NET_CANCELLED);
		assertThat(server.request(1).path()).isEqualTo("/v1/payments/netcancel");
		assertThat(server.request(1).body()).contains("\"orderId\":\"order-1\"");
	}

	@Test
	@DisplayName("승인 5xx → 망취소도 실패 - RESULT_UNKNOWN (관리자 확인)")
	void approve_serverError_netCancelFails() {
		server.enqueue(MockResponse.status(500, "{}"));
		server.enqueue(MockResponse.status(500, "{}"));

		assertThatThrownBy(() -> NicePayUtils.approve(approveRequest()))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.RESULT_UNKNOWN);
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("승인 연결 거부 - 요청 미전송이라 EXTERNAL_ERROR, 망취소 안 함")
	void approve_connectionRefused() {
		NicePayProperty property = new NicePayProperty();
		property.setClientKey(CLIENT_KEY);
		property.setSecretKey(SIGN_KEY);
		NicePayUtils.initialize(WebClientUtils.builder(MockHttpServer.closedPortUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1)).build(), property);

		assertThatThrownBy(() -> NicePayUtils.approve(approveRequest()))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.EXTERNAL_ERROR);
	}

	@Test
	@DisplayName("승인 요청 검증 실패 - BAD_REQUEST (NICEPAY 호출 전 차단)")
	void approve_validation() {
		NicePayDto.ApproveRequest invalid = NicePayDto.ApproveRequest.builder().tid(TID).orderId("order-1").amount(0).build();

		assertThatThrownBy(() -> NicePayUtils.approve(invalid))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(server.requestCount()).isZero();
	}

	// === 취소 / 조회 ===

	@Test
	@DisplayName("부분취소 성공 - cancelledTid, balanceAmt 확인")
	void cancel_partial() {
		server.enqueue(MockResponse.ok("{\"resultCode\":\"0000\",\"tid\":\"" + TID + "\",\"cancelledTid\":\"ctid_1\","
			+ "\"orderId\":\"order-1\",\"status\":\"partialCancelled\",\"amount\":10000,\"balanceAmt\":7000}"));

		NicePayDto.Payment result = NicePayUtils.cancel(TID,
			NicePayDto.CancelRequest.builder().reason("부분 환불").orderId("order-1-c1").cancelAmt(3000).build());

		assertThat(result.getPaymentStatus()).isEqualTo(NicePayDto.Status.PARTIAL_CANCELLED);
		assertThat(result.getCancelledTid()).isEqualTo("ctid_1");
		assertThat(result.getBalanceAmt()).isEqualTo(7000);
		assertThat(server.request(0).path()).isEqualTo("/v1/payments/" + TID + "/cancel");
		assertThat(server.request(0).body()).contains("\"cancelAmt\":3000").doesNotContain("refundHolder");
	}

	@Test
	@DisplayName("취소 결과불명 - 재시도 없이 RESULT_UNKNOWN (요청은 정확히 1번)")
	void cancel_serverError_noRetry() {
		server.enqueue(MockResponse.status(500, "{}"));

		assertThatThrownBy(() -> NicePayUtils.cancel(TID,
			NicePayDto.CancelRequest.builder().reason("고객 변심").orderId("order-1").build()))
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(NicePayError.RESULT_UNKNOWN);
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("조회 4xx - 본문 resultCode 를 NICEPAY_{code} 로 살린다")
	void getPayment_clientError() {
		server.enqueue(MockResponse.status(400, "{\"resultCode\":\"2152\",\"resultMsg\":\"거래가 존재하지 않습니다.\"}"));

		assertThatThrownBy(() -> NicePayUtils.getPayment(TID))
			.hasMessage("거래가 존재하지 않습니다.")
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("NICEPAY_2152");
		assertThat(server.request(0).method()).isEqualTo("GET");
	}

	@Test
	@DisplayName("설정 검증 - base-url 이 https 가 아니면 거부")
	void property_requiresHttps() {
		NicePayProperty property = new NicePayProperty();
		property.setClientKey(CLIENT_KEY);
		property.setSecretKey(SIGN_KEY);
		property.setBaseUrl("http://sandbox-api.nicepay.co.kr");

		assertThatThrownBy(property::validate)
			.extracting(NicePayUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.INTERNAL_SERVER);
	}
}
