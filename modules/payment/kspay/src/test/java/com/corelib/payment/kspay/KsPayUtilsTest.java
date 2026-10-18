// KsPayUtils 승인(통합모듈 MS949 백틱 응답)·재전송·대조 검증과 webfep 취소 검증 (로컬 HTTP 서버)
package com.corelib.payment.kspay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.webclient.WebClientUtils;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

class KsPayUtilsTest {

	private static final Charset MS949 = Charset.forName("x-windows-949");
	private static final String MID = "2999199999";
	private static final String PC_PATH = "/store/KSPayWebV1.4/web_host/recv_post.jsp";

	private MockHttpServer server;
	private KsPayProperty property;

	@BeforeEach
	void setUp() {
		server = MockHttpServer.start();
		property = new KsPayProperty();
		property.setMid(MID);
		property.setApiKey("pgapi-test-key");
		init(server.baseUrl());
	}

	private void init(String baseUrl) {
		WebClient approval = WebClientUtils.builder(baseUrl, Duration.ofSeconds(1), Duration.ofMillis(500)).build();
		WebClient webfep = WebClientUtils.builder(baseUrl, Duration.ofSeconds(1), Duration.ofMillis(500))
										 .defaultHeader(HttpHeaders.AUTHORIZATION, "pgapi " + property.getApiKey())
										 .build();
		KsPayUtils.initialize(property, approval, webfep);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private static KsPayDto.ApproveRequest request() {
		return KsPayDto.ApproveRequest.builder().payKey("pk-1").orderNo("order-1").amount(10000).channel(KsPayDto.Channel.PC).build();
	}

	/** 통합모듈 응답 재현 — 첫 토큰(버림) + 요청 필드 순서대로 값 + 꼬리 칸, MS949 인코딩 */
	private static MockResponse approvalResponse(Map<String, String> overrides) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("authyn", "O");
		values.put("trno", "T0000001");
		values.put("trddt", "20261018");
		values.put("trdtm", "101530");
		values.put("amt", "10000");
		values.put("authno", "30012345");
		values.put("msg1", "정상승인");
		values.put("msg2", "");
		values.put("isscd", "04");
		values.put("aqucd", "04");
		values.put("result", "1001");
		values.put("resultcd", "0000");
		values.put("halbu", "00");
		values.put("cardno", "377649XXXXXX101");
		values.put("storeid", MID);
		values.put("ordno", "order-1");
		values.putAll(overrides);
		String body = "`" + String.join("`", values.values()) + "`꼬리`";
		return MockResponse.bytes(200, body.getBytes(MS949), "text/html;charset=euc-kr");
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	// === 승인 ===

	@Test
	@DisplayName("승인 성공 - form 파라미터 전송, MS949 한글 해석, 상점아이디·주문번호·금액 대조 통과")
	void approve_success() {
		server.enqueue(approvalResponse(Map.of()));

		KsPayDto.Approval approval = KsPayUtils.approve(request());

		assertThat(approval.isSuccess()).isTrue();
		assertThat(approval.getTid()).isEqualTo("T0000001");
		assertThat(approval.getAmount()).isEqualTo(10000);
		assertThat(approval.getMessage1()).isEqualTo("정상승인");
		assertThat(approval.getMessage2()).isNull();
		assertThat(approval.getTradedAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 10, 15, 30));
		assertThat(approval.getPayMethod()).isEqualTo(KsPayDto.PayMethod.CARD);

		MockHttpServer.RecordedRequest sent = server.request(0);
		assertThat(sent.path()).isEqualTo(PC_PATH);
		assertThat(sent.header("Content-Type")).contains("application/x-www-form-urlencoded");
		assertThat(sent.body()).contains("sndCommConId=pk-1", "sndActionType=1", "sndAmount=10000", "sndRpyParams=authyn%60trno");
	}

	@Test
	@DisplayName("모바일 채널 - 모바일 승인 경로로 전송")
	void approve_mobileChannel() {
		server.enqueue(approvalResponse(Map.of()));

		KsPayUtils.approve(KsPayDto.ApproveRequest.builder().payKey("pk-1").orderNo("order-1").amount(10000)
			.channel(KsPayDto.Channel.MOBILE).build());

		assertThat(server.request(0).path()).isEqualTo("/store/KSPayMobileV1.4/web_host/recv_post.jsp");
	}

	@Test
	@DisplayName("승인 거절(authyn X) - KSPAY_{resultcd}, 메시지는 msg1+msg2 원문")
	void approve_declined() {
		server.enqueue(approvalResponse(Map.of("authyn", "X", "resultcd", "8326", "msg1", "한도초과", "msg2", "카드사문의")));

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.hasMessage("한도초과 카드사문의")
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("KSPAY_8326");
	}

	@Test
	@DisplayName("금액 불일치 - AMOUNT_MISMATCH (승인 성립, 취소 필요)")
	void approve_amountMismatch() {
		server.enqueue(approvalResponse(Map.of("amt", "9999")));

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.extracting(KsPayUtilsTest::errorCodeOf)
			.isEqualTo(KsPayError.AMOUNT_MISMATCH);
	}

	@Test
	@DisplayName("다른 상점아이디로 승인 - MID_MISMATCH")
	void approve_midMismatch() {
		server.enqueue(approvalResponse(Map.of("storeid", "2999100000")));

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.extracting(KsPayUtilsTest::errorCodeOf)
			.isEqualTo(KsPayError.MID_MISMATCH);
	}

	@Test
	@DisplayName("결제키 무효(모든 값 빈칸) - EXTERNAL_ERROR, 재전송 안 함")
	void approve_invalidPayKey() {
		String empty = "`" + "`".repeat(16) + "`";
		server.enqueue(MockResponse.bytes(200, empty.getBytes(MS949), "text/html"));

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.extracting(KsPayUtilsTest::errorCodeOf)
			.isEqualTo(KsPayError.EXTERNAL_ERROR);
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("결과불명(5xx) → 같은 결제키로 1회 재전송, 승인 확인되면 성공")
	void approve_serverError_resendApproved() {
		server.enqueue(MockResponse.status(502, "{}"));
		server.enqueue(approvalResponse(Map.of()));

		assertThat(KsPayUtils.approve(request()).isSuccess()).isTrue();
		assertThat(server.requestCount()).isEqualTo(2);
		assertThat(server.request(1).body()).isEqualTo(server.request(0).body());
	}

	@Test
	@DisplayName("결과불명(응답 형식 깨짐) → 재전송도 timeout - RESULT_UNKNOWN")
	void approve_broken_resendTimeout() {
		server.enqueue(MockResponse.bytes(200, "깨진응답".getBytes(MS949), "text/html"));
		server.enqueue(approvalResponse(Map.of()).delayed(Duration.ofSeconds(3)));

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.extracting(KsPayUtilsTest::errorCodeOf)
			.isEqualTo(KsPayError.RESULT_UNKNOWN);
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("결과불명 → 재전송 응답이 거절 - 첫 요청 승인 여부 불명이라 RESULT_UNKNOWN")
	void approve_unknown_resendDeclined() {
		server.enqueue(MockResponse.status(500, "{}"));
		server.enqueue(approvalResponse(Map.of("authyn", "X", "resultcd", "9999")));

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.extracting(KsPayUtilsTest::errorCodeOf)
			.isEqualTo(KsPayError.RESULT_UNKNOWN);
	}

	@Test
	@DisplayName("연결 거부 - 요청 미전송이라 EXTERNAL_ERROR, 재전송 안 함")
	void approve_connectionRefused() {
		init(MockHttpServer.closedPortUrl());

		assertThatThrownBy(() -> KsPayUtils.approve(request()))
			.extracting(KsPayUtilsTest::errorCodeOf)
			.isEqualTo(KsPayError.EXTERNAL_ERROR);
	}

	@Test
	@DisplayName("주문번호 byte 초과(모바일 30)·금지 문자 - BAD_REQUEST, 전송 안 함")
	void approve_invalidOrderNo() {
		KsPayDto.ApproveRequest tooLong = KsPayDto.ApproveRequest.builder().payKey("pk-1").orderNo("주문".repeat(8))
			.amount(10000).channel(KsPayDto.Channel.MOBILE).build();
		KsPayDto.ApproveRequest backtick = KsPayDto.ApproveRequest.builder().payKey("pk-1").orderNo("order`1")
			.amount(10000).channel(KsPayDto.Channel.PC).build();

		assertThatThrownBy(() -> KsPayUtils.approve(tooLong))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThatThrownBy(() -> KsPayUtils.approve(backtick))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(server.requestCount()).isZero();
	}

	// === 취소 (webfep) ===

	@Test
	@DisplayName("카드 부분취소 성공 - pgapi 인증, 가이드 키 전부 문자열로 전송(미사용은 \"\")")
	void cancel_cardPartial() {
		server.enqueue(MockResponse.ok("{\"aid\":\"a-1\",\"code\":\"A0200\",\"message\":\"Success\",\"data\":{\"tid\":\"T0000001\","
			+ "\"tradeDateTime\":\"20261018110000\",\"cancelAmount\":\"3000\",\"respCode\":\"0000\",\"respMessage\":\"승인취소완료\"}}"));

		KsPayDto.CancelResponse response = KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T0000001")
			.cancelType(KsPayDto.CancelType.PARTIAL).cancelAmount(3000).cancelSeq(1).build());

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.getData().getCancelAmountValue()).isEqualTo(3000);
		MockHttpServer.RecordedRequest sent = server.request(0);
		assertThat(sent.path()).isEqualTo("/kspay/webfep/api/v1/card/cancel");
		assertThat(sent.header("Authorization")).isEqualTo("pgapi pgapi-test-key");
		assertThat(sent.body()).contains("\"mid\":\"" + MID + "\"", "\"cancelType\":\"PARTIAL\"", "\"orgTradeKeyType\":\"TID\"",
			"\"orgTradeKey\":\"T0000001\"", "\"orgTradeDate\":\"\"", "\"cancelTotalAmount\":\"3000\"", "\"cancelSeq\":\"1\"",
			"\"cancelTaxAmount\":\"\"", "\"cancelTaxFreeAmount\":\"\"");
	}

	@Test
	@DisplayName("계좌이체 취소 - respCode 000(3자리)도 성공, 세금 키 미전송")
	void cancel_account() {
		server.enqueue(MockResponse.ok("{\"code\":\"A0200\",\"data\":{\"respCode\":\"000\",\"respMessage\":\"OK:이체취소\"}}"));

		KsPayDto.CancelResponse response = KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.ACCOUNT).tradeKeyType(KsPayDto.TradeKeyType.ORDER_NUMB).tradeKey("order-1")
			.tradeDt(LocalDate.of(2026, 10, 18)).build());

		assertThat(response.isSuccess()).isTrue();
		assertThat(server.request(0).path()).isEqualTo("/kspay/webfep/api/v1/account/cancel");
		assertThat(server.request(0).body()).contains("\"orgTradeDate\":\"20261018\"", "\"cancelType\":\"FULL\"")
			.doesNotContain("cancelTaxAmount");
	}

	@Test
	@DisplayName("가상계좌 회수 - recall 경로, cancelType·금액 키 없음")
	void cancel_vaccountRecall() {
		server.enqueue(MockResponse.ok("{\"code\":\"A0200\",\"data\":{\"respCode\":\"0000\",\"respMessage\":\"계좌취소완료\"}}"));

		KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.VACCOUNT).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T0000002").build());

		assertThat(server.request(0).path()).isEqualTo("/kspay/webfep/api/v1/vaccount/recall");
		assertThat(server.request(0).body()).doesNotContain("cancelType", "cancelTotalAmount");
	}

	@Test
	@DisplayName("취소 거절(A0201 + respCode) - KSPAY_{respCode}, 메시지는 respMessage")
	void cancel_rejected() {
		server.enqueue(MockResponse.ok("{\"aid\":\"a-2\",\"code\":\"A0201\",\"message\":\"Failed\","
			+ "\"data\":{\"respCode\":\"7003\",\"respMessage\":\"취소거절기간경과\"}}"));

		assertThatThrownBy(() -> KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T0000001").build()))
			.hasMessage("취소거절기간경과")
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("KSPAY_7003");
	}

	@Test
	@DisplayName("A0200 인데 respCode 실패(P10Q) - 성공으로 보지 않음")
	void cancel_a0200ButRejected() {
		server.enqueue(MockResponse.ok("{\"code\":\"A0200\",\"message\":\"Fail\",\"data\":{\"respCode\":\"P10Q\",\"respMessage\":\"취소거절\"}}"));

		assertThatThrownBy(() -> KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T0000001").build()))
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("KSPAY_P10Q");
	}

	@Test
	@DisplayName("A0500 서버 오류·통신 결과불명 - 재시도 없이 RESULT_UNKNOWN")
	void cancel_unknown_noRetry() {
		server.enqueue(MockResponse.ok("{\"code\":\"A0500\",\"message\":\"server error\"}"));
		server.enqueue(MockResponse.status(500, "{}"));
		KsPayDto.CancelRequest request = KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T0000001").build();

		assertThatThrownBy(() -> KsPayUtils.cancel(request))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(KsPayError.RESULT_UNKNOWN);
		assertThatThrownBy(() -> KsPayUtils.cancel(request))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(KsPayError.RESULT_UNKNOWN);
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("취소 조합 검증 - 휴대폰 부분취소·주문번호 취소 날짜 누락·전액취소에 금액 지정은 BAD_REQUEST")
	void cancel_validation() {
		assertThatThrownBy(() -> KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.MOBILE).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T1")
			.cancelType(KsPayDto.CancelType.PARTIAL).cancelAmount(100).cancelSeq(1).build()))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThatThrownBy(() -> KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.ORDER_NUMB).tradeKey("order-1").build()))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThatThrownBy(() -> KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T1").cancelAmount(100).build()))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(server.requestCount()).isZero();
	}

	@Test
	@DisplayName("api-key 미설정 - 취소는 설정 오류 (승인은 키 없이 가능)")
	void cancel_requiresApiKey() {
		property.setApiKey(null);

		assertThatThrownBy(() -> KsPayUtils.cancel(KsPayDto.CancelRequest.builder()
			.payMethod(KsPayDto.PayMethod.CARD).tradeKeyType(KsPayDto.TradeKeyType.TID).tradeKey("T1").build()))
			.extracting(KsPayUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.INTERNAL_SERVER);
	}
}
