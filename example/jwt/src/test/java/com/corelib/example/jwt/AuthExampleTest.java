package com.corelib.example.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"example.admin.email=admin@example.com", "example.admin.password=admin-pass-1"})
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class AuthExampleTest {

	private final MockMvc mockMvc;

	@DynamicPropertySource
	static void key(DynamicPropertyRegistry registry) {
		registry.add("core.jwt.private-key", () -> PemUtils.writePrivateKey(RsaUtils.generateKeyPair(2048).getPrivate()));
	}

	private ResultActions postJson(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions login(String email, String password) throws Exception {
		return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	/** 같은 요청을 threads 개 스레드에서 한꺼번에 보내고 응답 본문을 모은다 — 모두 준비된 뒤 동시에 출발 */
	private static List<String> concurrently(int threads, Callable<String> request) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return request.call();
				}));
			}
			ready.await();
			start.countDown();
			List<String> bodies = new ArrayList<>();
			for (Future<String> future : futures) {
				bodies.add(future.get(30, TimeUnit.SECONDS));
			}
			return bodies;
		} finally {
			executor.shutdownNow();
		}
	}

	/** 가입 후 로그인 — 토큰 응답 JSON */
	private String signupAndLogin(String email) throws Exception {
		postJson("/api/auth/signup", "{\"email\":\"" + email + "\",\"password\":\"pass-word-1\",\"nickname\":\"길동\"}").andExpect(status().isOk());
		return login(email, "pass-word-1")
			.andExpect(jsonPath("$.data.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.data.accessTokenExpiresIn").value(1800))
			.andReturn().getResponse().getContentAsString();
	}

	@Test
	@DisplayName("가입 → 로그인 → Bearer 로 내 정보, 토큰 없으면 C401")
	void loginAndMe() throws Exception {
		String access = JsonPath.read(signupAndLogin("me@example.com"), "$.data.accessToken");

		mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + access))
			   .andExpect(jsonPath("$.data.email").value("me@example.com"))
			   .andExpect(jsonPath("$.data.roles[0]").value("USER"));
		mockMvc.perform(get("/api/members/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("C401"));
	}

	@Test
	@DisplayName("로그인 실패 — 비밀번호 틀림·없는 이메일 모두 같은 401")
	void loginFailed() throws Exception {
		signupAndLogin("wrong@example.com");
		postJson("/api/auth/login", "{\"email\":\"wrong@example.com\",\"password\":\"nope-nope\"}")
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
		postJson("/api/auth/login", "{\"email\":\"ghost@example.com\",\"password\":\"pass-word-1\"}")
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
	}

	@Test
	@DisplayName("로그인 요청 - email 101자·password 65자·이메일 형식 아님은 400")
	void loginRequestLimits() throws Exception {
		String longEmail = "a".repeat(90) + "@example.com";    // 102자
		postJson("/api/auth/login", "{\"email\":\"" + longEmail + "\",\"password\":\"pass-word-1\"}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C400"));
		postJson("/api/auth/login", "{\"email\":\"ok@example.com\",\"password\":\"" + "p".repeat(65) + "\"}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C400"));
		postJson("/api/auth/login", "{\"email\":\"not-an-email\",\"password\":\"pass-word-1\"}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C400"));
	}

	@Test
	@DisplayName("리프레시 회전 — 새 쌍 발급, 옛 리프레시를 다시 쓰면 탈취로 보고 새 것까지 폐기")
	void refreshRotation() throws Exception {
		String first = signupAndLogin("rotate@example.com");
		String oldRefresh = JsonPath.read(first, "$.data.refreshToken");

		String second = postJson("/api/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String newRefresh = JsonPath.read(second, "$.data.refreshToken");

		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}")
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_REJECTED"));
		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + newRefresh + "\"}")
			.andExpect(status().isUnauthorized());    // 재사용이 감지돼 새 토큰도 무효 — 다시 로그인
	}

	@Test
	@DisplayName("같은 리프레시 동시 2건 — 1건만 새 쌍, 나머지는 재사용으로 판정돼 이긴 쪽 새 토큰까지 폐기")
	void concurrentRefresh() throws Exception {
		String refresh = JsonPath.read(signupAndLogin("race@example.com"), "$.data.refreshToken");

		List<String> bodies = concurrently(2, () -> postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}")
			.andReturn().getResponse().getContentAsString());

		assertThat(bodies).extracting(body -> JsonPath.<String>read(body, "$.code")).containsExactlyInAnyOrder(null, "REFRESH_REJECTED");
		String winner = bodies.stream().filter(body -> JsonPath.<Boolean>read(body, "$.success")).findFirst().orElseThrow();
		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + JsonPath.read(winner, "$.data.refreshToken") + "\"}")
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_REJECTED"));
	}

	@Test
	@DisplayName("두 기기 로그인 — 각자 갱신 성공 / 한 기기에서 옛 리프레시를 재사용하면 두 기기 모두 폐기")
	void twoDevices() throws Exception {
		String phone = signupAndLogin("multi@example.com");
		String laptop = login("multi@example.com", "pass-word-1").andReturn().getResponse().getContentAsString();
		String phoneOld = JsonPath.read(phone, "$.data.refreshToken");

		String phoneNew = JsonPath.read(postJson("/api/auth/refresh", "{\"refreshToken\":\"" + phoneOld + "\"}")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.refreshToken");
		String laptopNew = JsonPath.read(postJson("/api/auth/refresh", "{\"refreshToken\":\"" + JsonPath.read(laptop, "$.data.refreshToken") + "\"}")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.refreshToken");

		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + phoneOld + "\"}")
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_REJECTED"));
		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + phoneNew + "\"}").andExpect(status().isUnauthorized());
		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + laptopNew + "\"}").andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("로그인 5번 실패 — 잠금(429), 맞는 비밀번호도 거부 / 없는 이메일도 똑같이 잠금")
	void loginLocked() throws Exception {
		signupAndLogin("lock@example.com");
		for (String email : List.of("lock@example.com", "ghost-lock@example.com")) {
			for (int i = 1; i <= 5; i++) {
				login(email, "nope-nope").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
			}
			login(email, "pass-word-1").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
		}
	}

	@Test
	@DisplayName("로그아웃 — 리프레시 폐기 / 액세스 토큰을 갱신 API 에 넣으면 JWT-TYPE")
	void logout() throws Exception {
		String tokens = signupAndLogin("bye@example.com");
		String access = JsonPath.read(tokens, "$.data.accessToken");
		String refresh = JsonPath.read(tokens, "$.data.refreshToken");

		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + access + "\"}")
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("JWT-TYPE"));
		mockMvc.perform(post("/api/members/logout").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
		postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}").andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("관리자 API — USER 403, 관리자 200(이메일 마스킹) / JWKS 는 로그인 없이 공개")
	void adminAndJwks() throws Exception {
		String userAccess = JsonPath.read(signupAndLogin("user@example.com"), "$.data.accessToken");
		mockMvc.perform(get("/api/admin/members/list").header("Authorization", "Bearer " + userAccess))
			   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("C403"));

		String adminTokens = postJson("/api/auth/login", "{\"email\":\"admin@example.com\",\"password\":\"admin-pass-1\"}")
			.andReturn().getResponse().getContentAsString();
		mockMvc.perform(get("/api/admin/members/list").header("Authorization", "Bearer " + JsonPath.read(adminTokens, "$.data.accessToken")))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data[?(@.nickname == '관리자')].email").value("ad***@example.com"));

		mockMvc.perform(get("/.well-known/jwks.json"))
			   .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
			   .andExpect(jsonPath("$.keys[0].d").doesNotExist());
	}
}
