package com.corelib.example.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.auth.otp.OtpUtils;
import com.corelib.common.exception.CoreException;
import com.corelib.example.auth.otp.service.OtpExampleService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "example.otp.max-failures=3")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class OtpExampleTest {

	private final MockMvc mockMvc;

	private ResultActions postJson(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** 등록 시작 → 응답의 비밀키(OTP 앱이 QR 에서 읽는 값) */
	private String setup(String memberId) throws Exception {
		String body = postJson("/api/otp/setup", "{\"memberId\":\"" + memberId + "\",\"accountName\":\"hong@example.com\"}")
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String uri = JsonPath.read(body, "$.data.otpauthUri");
		assertThat(uri).startsWith("otpauth://totp/").contains("issuer=core-lib%20%EC%98%88%EC%A0%9C");
		String manualKey = JsonPath.read(body, "$.data.manualKey");
		assertThat(uri).contains("secret=" + manualKey.replace(" ", ""));
		return manualKey.replace(" ", "");
	}

	/** OTP 앱이 지금 보여 줄 코드 */
	private static String appCode(String secret, int offsetSeconds) {
		return OtpUtils.generateCode(secret, Instant.now().plusSeconds(offsetSeconds));
	}

	private String codeJson(String memberId, String code) {
		return "{\"memberId\":\"" + memberId + "\",\"code\":\"" + code + "\"}";
	}

	/** 같은 요청 count 건을 동시에 보내고 응답 상태 코드를 모은다 */
	private List<Integer> postConcurrently(int count, String path, String json) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return postJson(path, json).andReturn().getResponse().getStatus();
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(10, TimeUnit.SECONDS));
			}
			return statuses;
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("등록 → 활성화 → 로그인 검증, 같은 코드 재사용은 거부")
	void setupActivateVerify() throws Exception {
		String secret = setup("m-1");
		mockMvc.perform(get("/api/otp/status").param("memberId", "m-1")).andExpect(jsonPath("$.data.enabled").value(false));

		postJson("/api/otp/activate", codeJson("m-1", appCode(secret, -30))).andExpect(jsonPath("$.data.enabled").value(true));
		postJson("/api/otp/verify", codeJson("m-1", appCode(secret, 0))).andExpect(status().isOk());
		postJson("/api/otp/verify", codeJson("m-1", appCode(secret, 0)))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
	}

	@Test
	@DisplayName("연속 실패 — 3번째에 잠금(423), 잠긴 동안은 맞는 코드도 거부")
	void lockAfterFailures() throws Exception {
		String secret = setup("m-2");
		postJson("/api/otp/activate", codeJson("m-2", appCode(secret, 0))).andExpect(status().isOk());
		String wrong = appCode(secret, 0).equals("000000") ? "111111" : "000000";

		postJson("/api/otp/verify", codeJson("m-2", wrong)).andExpect(jsonPath("$.message").value("인증 코드가 올바르지 않습니다. (1/3)"));
		postJson("/api/otp/verify", codeJson("m-2", wrong)).andExpect(status().isUnauthorized());
		postJson("/api/otp/verify", codeJson("m-2", wrong)).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("OTP_LOCKED"));
		postJson("/api/otp/verify", codeJson("m-2", appCode(secret, 30))).andExpect(status().isLocked());
		mockMvc.perform(get("/api/otp/status").param("memberId", "m-2")).andExpect(jsonPath("$.data.lockedUntil").isNotEmpty());
	}

	@Test
	@DisplayName("병렬 오답 10건 — 동시에 보내도 오답 2번 뒤 3번째에 잠기고 나머지는 잠금(423)")
	void lockAfterParallelFailures() throws Exception {
		String secret = setup("m-5");
		postJson("/api/otp/activate", codeJson("m-5", appCode(secret, 0))).andExpect(status().isOk());
		String wrong = appCode(secret, 0).equals("000000") ? "111111" : "000000";

		List<Integer> statuses = postConcurrently(10, "/api/otp/verify", codeJson("m-5", wrong));

		assertThat(statuses).filteredOn(code -> code == 401).hasSize(2);
		assertThat(statuses).filteredOn(code -> code == 423).hasSize(8);
		mockMvc.perform(get("/api/otp/status").param("memberId", "m-5")).andExpect(jsonPath("$.data.lockedUntil").isNotEmpty());
	}

	@Test
	@DisplayName("같은 유효 코드를 동시에 보내면 1건만 통과 (재사용 차단)")
	void sameCodeConcurrently() throws Exception {
		String secret = setup("m-6");
		postJson("/api/otp/activate", codeJson("m-6", appCode(secret, -30))).andExpect(status().isOk());

		List<Integer> statuses = postConcurrently(8, "/api/otp/verify", codeJson("m-6", appCode(secret, 0)));

		assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
	}

	@Test
	@DisplayName("이미 켜진 상태에서 다시 활성화 — 400, 거부된 코드는 소비되지 않는다")
	void activateTwice() throws Exception {
		String secret = setup("m-7");
		postJson("/api/otp/activate", codeJson("m-7", appCode(secret, -30))).andExpect(status().isOk());

		postJson("/api/otp/activate", codeJson("m-7", appCode(secret, 0)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_ALREADY_ENABLED"));
		postJson("/api/otp/verify", codeJson("m-7", appCode(secret, 0))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("암호화 키가 base64 32byte 가 아니면 기동 실패")
	void secretKeyMustBe32Bytes() {
		String key16 = Base64.getEncoder().encodeToString(new byte[16]);
		String key32 = Base64.getEncoder().encodeToString(new byte[32]);

		assertThatThrownBy(() -> new OtpExampleService(key16, 5, Duration.ofMinutes(5)))
			.isInstanceOf(CoreException.class).hasMessageContaining("example.otp.secret-key").hasMessageContaining("32byte");
		assertThatCode(() -> new OtpExampleService(key32, 5, Duration.ofMinutes(5))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("켜진 상태에서 재등록 거부, 해제는 현재 코드가 맞아야, 해제 후 검증은 등록 정보 없음(404)")
	void disable() throws Exception {
		String secret = setup("m-3");
		postJson("/api/otp/activate", codeJson("m-3", appCode(secret, -30))).andExpect(status().isOk());

		postJson("/api/otp/setup", "{\"memberId\":\"m-3\",\"accountName\":\"x\"}").andExpect(status().isBadRequest());
		postJson("/api/otp/disable", codeJson("m-3", appCode(secret, 0))).andExpect(jsonPath("$.data.enabled").value(false));
		postJson("/api/otp/verify", codeJson("m-3", appCode(secret, 30))).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("형식 오류 — 숫자 아닌 코드는 C400")
	void invalidFormat() throws Exception {
		setup("m-4");
		postJson("/api/otp/activate", codeJson("m-4", "abcdef")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C400"));
	}
}
