// NICE 인증 예제를 실제 앱으로 띄워 인증 → 회원 가입/로그인 → 결과 조회 전 흐름 검증 (가짜 NICE 서버)
package com.corelib.example.auth.nice;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class NiceAuthExampleTest {

	static final MockHttpServer NICE = MockHttpServer.start();
	private static final String TICKET = "ticket-1";
	private static final String TRANSACTION_ID = "tx-1";
	private static final int ITERATORS = 66;

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("auth.nice.base-url", NICE::baseUrl);
		registry.add("auth.nice.client-id", () -> "nice-client");
		registry.add("auth.nice.client-secret", () -> "ns-test");
	}

	@AfterAll
	static void closeServer() {
		NICE.close();
	}

	private static String b64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** 가이드 규칙대로 NICE 결과를 암호화 — {enc_data, integrity_value} */
	private static String[] niceEncrypt(String plainJson) throws Exception {
		String keyString = b64url(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
			.generateSecret(new PBEKeySpec(TICKET.toCharArray(), TRANSACTION_ID.getBytes(StandardCharsets.UTF_8), ITERATORS, 512))
			.getEncoded());
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

	private static void enqueueStart() {
		NICE.enqueue(MockResponse.ok("{\"result_code\":\"0000\",\"access_token\":\"at-1\",\"expires_in\":"
			+ (System.currentTimeMillis() + 86_400_000L) + ",\"iterators\":" + ITERATORS + ",\"ticket\":\"" + TICKET + "\"}"));
		NICE.enqueue(MockResponse.ok("{\"result_code\":\"0000\",\"auth_url\":\"https://auth.niceid.co.kr/ido/cert/request/S1\","
			+ "\"transaction_id\":\"" + TRANSACTION_ID + "\"}"));
	}

	/** 시작 → 표준창(가짜) → return_url 콜백까지 진행하고 콜백 응답의 Location 을 돌려준다 */
	private String authenticate(MockHttpSession session, String personJson) throws Exception {
		enqueueStart();
		String[] encrypted = niceEncrypt(personJson);
		NICE.enqueue(MockResponse.ok("{\"result_code\":\"0000\",\"enc_data\":\"" + encrypted[0] + "\",\"integrity_value\":\"" + encrypted[1] + "\"}"));

		mockMvc.perform(post("/api/nice-auth/start").session(session))
			   .andExpect(jsonPath("$.data.authUrl").value("https://auth.niceid.co.kr/ido/cert/request/S1"))
			   .andExpect(jsonPath("$.data.txNo").doesNotExist());
		return mockMvc.perform(get("/nice-auth/return").param("web_transaction_id", "web-1").session(session))
					  .andExpect(status().isFound())
					  .andReturn().getResponse().getHeader("Location");
	}

	private static String person(String ci, String birthdate, String mobileNo) {
		return "{\"name\":\"홍길동\",\"birthdate\":\"" + birthdate + "\",\"gender\":\"1\",\"national_info\":\"0\","
			+ "\"ci\":\"" + ci + "\",\"di\":\"DI-VALUE\",\"mobile_co\":\"1\",\"mobile_no\":\"" + mobileNo + "\"}";
	}

	@Test
	@DisplayName("첫 인증은 가입, 같은 CI 로 다시 인증하면 같은 회원으로 로그인(휴대폰번호 갱신), 결과는 마스킹")
	void joinThenLogin() throws Exception {
		MockHttpSession session = new MockHttpSession();
		assertThat(authenticate(session, person("CI-JOIN", "19900101", "01012345678"))).isEqualTo("/done.html?result=success");
		String body = mockMvc.perform(get("/api/nice-auth/result").session(session))
							 .andExpect(jsonPath("$.data.isNewMember").value(true))
							 .andExpect(jsonPath("$.data.maskedName").value("홍*동"))
							 .andExpect(jsonPath("$.data.maskedMobileNo").value("010-****-5678"))
							 .andExpect(jsonPath("$.data.birthYear").value(1990))
							 .andReturn().getResponse().getContentAsString();
		Integer memberId = JsonPath.read(body, "$.data.memberId");

		// 같은 콜백을 다시 열면 txNo 가 없어 실패 페이지로
		mockMvc.perform(get("/nice-auth/return").param("web_transaction_id", "web-1").session(session))
			   .andExpect(header().string("Location", startsWith("/done.html?result=fail")));

		MockHttpSession again = new MockHttpSession();
		assertThat(authenticate(again, person("CI-JOIN", "19900101", "01099998888"))).isEqualTo("/done.html?result=success");
		mockMvc.perform(get("/api/nice-auth/result").session(again))
			   .andExpect(jsonPath("$.data.isNewMember").value(false))
			   .andExpect(jsonPath("$.data.memberId").value(memberId))
			   .andExpect(jsonPath("$.data.maskedMobileNo").value("010-****-8888"));

		String members = mockMvc.perform(get("/api/nice-auth/members/list"))
								.andReturn().getResponse().getContentAsString();
		assertThat(members).contains("\"memberId\":" + memberId).doesNotContain("01099998888", "홍길동", "CI-JOIN");
	}

	@Test
	@DisplayName("만 14세 미만 - 가입 차단, 결과 없음")
	void underAge() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String birthdate = LocalDate.now().minusYears(10).format(DateTimeFormatter.BASIC_ISO_DATE);

		assertThat(authenticate(session, person("CI-CHILD", birthdate, "01011112222"))).startsWith("/done.html?result=fail");
		mockMvc.perform(get("/api/nice-auth/result").session(session))
			   .andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("인증 시작 없이 결과 조회 - 404")
	void resultWithoutAuth() throws Exception {
		mockMvc.perform(get("/api/nice-auth/result").session(new MockHttpSession()))
			   .andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("기동 안내 - NiceAuthUtils 보고")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("NiceAuthUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isEqualTo(true);
		assertThat(report.getDetail()).contains("인증수단=[M]");
	}
}
