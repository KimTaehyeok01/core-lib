package com.corelib.example.jwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.jayway.jsonpath.JsonPath;
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

	/** 가입 후 로그인 — 토큰 응답 JSON */
	private String signupAndLogin(String email) throws Exception {
		postJson("/api/auth/signup", "{\"email\":\"" + email + "\",\"password\":\"pass-word-1\",\"nickname\":\"길동\"}").andExpect(status().isOk());
		return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"pass-word-1\"}")
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
