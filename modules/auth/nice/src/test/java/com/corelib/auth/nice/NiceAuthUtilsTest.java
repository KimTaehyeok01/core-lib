package com.corelib.auth.nice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.webclient.WebClientUtils;
import com.corelib.webclient.fixture.MockHttpServer;
import com.corelib.webclient.fixture.MockHttpServer.MockResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NiceAuthUtilsTest {

	private static final String CLIENT_ID = "nice-client";
	private static final String CLIENT_SIGN = "ns-test";
	private static final String TICKET = "ticket-1";
	private static final String TRANSACTION_ID = "tx-1";
	private static final int ITERATORS = 66;
	private static final Pattern REQUEST_NO = Pattern.compile("\"request_no\":\"([^\"]+)\"");

	private MockHttpServer server;

	@BeforeEach
	void setUp() {
		server = MockHttpServer.start();
		NiceAuthProperty property = new NiceAuthProperty();
		property.setClientId(CLIENT_ID);
		property.setClientSecret(CLIENT_SIGN);
		property.setReturnUrl("https://example.com/nice/return");
		NiceAuthUtils.initialize(WebClientUtils.builder(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1)).build(), property,
			property.callTimeout());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	// === 가이드 규칙을 테스트 쪽에서 따로 구현한 가짜 NICE 암호화 (구현 코드와 독립) ===

	private static String b64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** @return {enc_data, integrity_value} */
	private static String[] niceEncrypt(String plainJson) throws Exception {
		byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
			.generateSecret(new PBEKeySpec(TICKET.toCharArray(), TRANSACTION_ID.getBytes(StandardCharsets.UTF_8), ITERATORS, 512))
			.getEncoded();
		String keyString = b64url(derived);
		byte[] iv = new byte[16];
		new SecureRandom().nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyString.substring(0, 32).getBytes(StandardCharsets.US_ASCII), "AES"),
			new GCMParameterSpec(128, iv));
		byte[] cipherText = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
		String encData = b64url(ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array());
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(keyString.substring(48, 80).getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
		return new String[] {encData, b64url(mac.doFinal(encData.getBytes(StandardCharsets.US_ASCII)))};
	}

	private void enqueueTokenAndUrl() {
		long expiresIn = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
		server.enqueue(MockResponse.ok("{\"result_code\":\"0000\",\"result_message\":\"응답성공\",\"access_token\":\"at-1\","
			+ "\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\",\"iterators\":" + ITERATORS + ",\"ticket\":\"" + TICKET + "\"}"));
		server.enqueue(MockResponse.ok("{\"result_code\":\"0000\",\"auth_url\":\"https://auth.niceid.co.kr/ido/cert/request/S1\","
			+ "\"transaction_id\":\"" + TRANSACTION_ID + "\"}"));
	}

	private static String requestNoOf(String body) {
		Matcher matcher = REQUEST_NO.matcher(body);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private static String resultBody(String encData, String integrity) {
		return "{\"result_code\":\"0000\",\"result_message\":\"응답성공\",\"enc_data\":\"" + encData
			+ "\",\"integrity_value\":\"" + integrity + "\"}";
	}

	private static NiceAuthDto.VerificationConfirmRequest confirm(String txNo) {
		return NiceAuthDto.VerificationConfirmRequest.builder().txNo(txNo).webTransactionId("web-1").build();
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	// === 테스트 ===

	@Test
	@DisplayName("인증 요청 - Basic(base64url)·Bearer·DevLang 헤더, token/url 의 request_no 는 서로 다름")
	void verification() {
		enqueueTokenAndUrl();

		NiceAuthDto.VerificationResponse response = NiceAuthUtils.verification(null);

		MockHttpServer.RecordedRequest token = server.request(0);
		MockHttpServer.RecordedRequest url = server.request(1);
		assertThat(token.path()).isEqualTo("/ido/intc/v1.0/auth/token");
		assertThat(token.header("Authorization"))
			.isEqualTo("Basic " + b64url((CLIENT_ID + ":" + CLIENT_SIGN).getBytes(StandardCharsets.UTF_8)));
		assertThat(token.header("X-Intc-DevLang")).isEqualTo(NiceAuthProperty.DEFAULT_DEV_LANG);
		assertThat(token.body()).contains("\"grant_type\":\"client_credentials\"");
		assertThat(url.path()).isEqualTo("/ido/intc/v1.0/auth/url");
		assertThat(url.header("Authorization")).isEqualTo("Bearer at-1");
		assertThat(url.body()).contains("\"return_url\":\"https://example.com/nice/return\"", "\"svc_types\":[\"M\"]",
			"\"method_type\":\"GET\"");

		assertThat(response.getAuthUrl()).isEqualTo("https://auth.niceid.co.kr/ido/cert/request/S1");
		assertThat(response.getTxNo()).isNotBlank();
		assertThat(response.getRequestNo()).hasSize(30).isEqualTo(requestNoOf(url.body()));
		assertThat(requestNoOf(token.body())).isNotEqualTo(requestNoOf(url.body()));
	}

	@Test
	@DisplayName("결과 확인 - 표준창 request_no 로 조회, 무결성 검증 후 복호화, 개인정보는 toString 에 안 나옴")
	void verificationConfirm() throws Exception {
		enqueueTokenAndUrl();
		String[] encrypted = niceEncrypt("{\"name\":\"홍길동\",\"birthdate\":\"19900101\",\"gender\":\"1\",\"national_info\":\"0\","
			+ "\"ci\":\"CI-VALUE\",\"di\":\"DI-VALUE\",\"mobile_co\":\"1\",\"mobile_no\":\"01012345678\"}");
		server.enqueue(MockResponse.ok(resultBody(encrypted[0], encrypted[1])));

		String txNo = NiceAuthUtils.verification(null).getTxNo();
		NiceAuthDto.VerificationConfirmResponse response = NiceAuthUtils.verificationConfirm(confirm(txNo));

		MockHttpServer.RecordedRequest result = server.request(2);
		assertThat(result.path()).isEqualTo("/ido/intc/v1.0/auth/result");
		assertThat(result.header("Authorization")).isEqualTo("Bearer at-1");
		assertThat(result.body()).contains("\"web_transaction_id\":\"web-1\"", "\"transaction_id\":\"" + TRANSACTION_ID + "\"");
		assertThat(requestNoOf(result.body())).isEqualTo(requestNoOf(server.request(1).body()));

		assertThat(response.getName()).isEqualTo("홍길동");
		assertThat(response.getBirthdate()).isEqualTo("19900101");
		assertThat(response.getCi()).isEqualTo("CI-VALUE");
		assertThat(response.getDi()).isEqualTo("DI-VALUE");
		assertThat(response.getMobileNo()).isEqualTo("01012345678");
		assertThat(response.toString()).doesNotContain("홍길동", "01012345678", "CI-VALUE");
	}

	@Test
	@DisplayName("무결성 값 변조 - INTEGRITY_FAILED (복호화 전에 차단)")
	void integrityTampered() throws Exception {
		enqueueTokenAndUrl();
		String[] encrypted = niceEncrypt("{\"ci\":\"CI\",\"di\":\"DI\"}");
		server.enqueue(MockResponse.ok(resultBody(encrypted[0], "AAAA" + encrypted[1].substring(4))));

		String txNo = NiceAuthUtils.verification(null).getTxNo();

		assertThatThrownBy(() -> NiceAuthUtils.verificationConfirm(confirm(txNo)))
			.extracting(NiceAuthUtilsTest::errorCodeOf)
			.isEqualTo(NiceAuthError.INTEGRITY_FAILED);
	}

	@Test
	@DisplayName("암호문 변조 - HMAC 이 문자열 기준이라 INTEGRITY_FAILED")
	void encDataTampered() throws Exception {
		enqueueTokenAndUrl();
		String[] encrypted = niceEncrypt("{\"ci\":\"CI\",\"di\":\"DI\"}");
		char swapped = encrypted[0].charAt(20) == 'A' ? 'B' : 'A';
		server.enqueue(MockResponse.ok(resultBody(encrypted[0].substring(0, 20) + swapped + encrypted[0].substring(21), encrypted[1])));

		String txNo = NiceAuthUtils.verification(null).getTxNo();

		assertThatThrownBy(() -> NiceAuthUtils.verificationConfirm(confirm(txNo)))
			.extracting(NiceAuthUtilsTest::errorCodeOf)
			.isEqualTo(NiceAuthError.INTEGRITY_FAILED);
	}

	@Test
	@DisplayName("CI·DI 없는 결과 - RESULT_INCOMPLETE")
	void resultIncomplete() throws Exception {
		enqueueTokenAndUrl();
		String[] encrypted = niceEncrypt("{\"name\":\"홍길동\"}");
		server.enqueue(MockResponse.ok(resultBody(encrypted[0], encrypted[1])));

		String txNo = NiceAuthUtils.verification(null).getTxNo();

		assertThatThrownBy(() -> NiceAuthUtils.verificationConfirm(confirm(txNo)))
			.extracting(NiceAuthUtilsTest::errorCodeOf)
			.isEqualTo(NiceAuthError.RESULT_INCOMPLETE);
	}

	@Test
	@DisplayName("거래번호 위변조 - TX_INVALID (결과 조회 호출 안 함)")
	void txTampered() {
		enqueueTokenAndUrl();
		String txNo = NiceAuthUtils.verification(null).getTxNo();
		String tampered = txNo.substring(0, txNo.length() - 2) + (txNo.endsWith("AA") ? "BB" : "AA");

		assertThatThrownBy(() -> NiceAuthUtils.verificationConfirm(confirm(tampered)))
			.extracting(NiceAuthUtilsTest::errorCodeOf)
			.isEqualTo(NiceAuthError.TX_INVALID);
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("결과 재조회(3033 기제공 건) - NICE_3033, 사용자 재시도 대상이라 400")
	void duplicateResult() {
		enqueueTokenAndUrl();
		server.enqueue(MockResponse.ok("{\"result_code\":\"3033\",\"result_message\":\"기 인증결과 제공 건\"}"));

		String txNo = NiceAuthUtils.verification(null).getTxNo();

		assertThatThrownBy(() -> NiceAuthUtils.verificationConfirm(confirm(txNo)))
			.hasMessage("기 인증결과 제공 건")
			.satisfies(e -> {
				assertThat(errorCodeOf(e).code()).isEqualTo("NICE_3033");
				assertThat(errorCodeOf(e).status()).isEqualTo(400);
			});
	}

	@Test
	@DisplayName("토큰 거절(HTTP 400 + 1007 IP 미허용) - NICE_1007, 설정 문제라 502")
	void tokenRejected() {
		server.enqueue(MockResponse.status(400, "{\"result_code\":\"1007\",\"result_message\":\"허용되지 않은 IP\"}"));

		assertThatThrownBy(() -> NiceAuthUtils.verification(null))
			.satisfies(e -> {
				assertThat(errorCodeOf(e).code()).isEqualTo("NICE_1007");
				assertThat(errorCodeOf(e).status()).isEqualTo(502);
			});
	}

	@Test
	@DisplayName("전체 상한 초과 - 읽기 timeout 전이어도 EXTERNAL_ERROR (연결·읽기+여유 5초)")
	void verification_callTimeout_externalError() {
		// 읽기 timeout(10초)보다 전체 상한(300ms)이 먼저 끊는다
		NiceAuthProperty property = new NiceAuthProperty();
		property.setClientId(CLIENT_ID);
		property.setClientSecret(CLIENT_SIGN);
		property.setReturnUrl("https://example.com/nice/return");
		NiceAuthUtils.initialize(
			WebClientUtils.builder(server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(10)).build(),
			property, Duration.ofMillis(300));
		server.enqueue(MockResponse.ok("{\"result_code\":\"0000\",\"access_token\":\"at-1\",\"expires_in\":1,\"ticket\":\"t\",\"iterators\":1}")
			.delayed(Duration.ofSeconds(2)));

		assertThatThrownBy(() -> NiceAuthUtils.verification(null))
			.extracting(NiceAuthUtilsTest::errorCodeOf)
			.isEqualTo(NiceAuthError.EXTERNAL_ERROR);
	}

	@Test
	@DisplayName("전체 상한 - 연결 + 읽기 + 5초")
	void property_callTimeout() {
		NiceAuthProperty property = new NiceAuthProperty();

		assertThat(property.callTimeout()).isEqualTo(Duration.ofSeconds(40));
	}

	@Test
	@DisplayName("잘못된 return_url·request_no - BAD_REQUEST (토큰 발급 전에 차단)")
	void invalidRequest() {
		NiceAuthDto.VerificationRequest badUrl = NiceAuthDto.VerificationRequest.builder().returnUrl("javascript:alert(1)").build();
		NiceAuthDto.VerificationRequest shortNo = NiceAuthDto.VerificationRequest.builder().requestNo("short").build();

		assertThatThrownBy(() -> NiceAuthUtils.verification(badUrl))
			.extracting(NiceAuthUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThatThrownBy(() -> NiceAuthUtils.verification(shortNo))
			.extracting(NiceAuthUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(server.requestCount()).isZero();
	}
}
