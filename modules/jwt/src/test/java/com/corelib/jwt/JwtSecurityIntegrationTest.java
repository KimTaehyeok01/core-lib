package com.corelib.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.response.ApiResponse;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.corelib.web.CoreSecurityCustomizer;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = JwtSecurityIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class JwtSecurityIntegrationTest {

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("core.jwt.private-key", () -> PemUtils.writePrivateKey(RsaUtils.generateKeyPair(2048).getPrivate()));
		registry.add("core.jwt.issuer", () -> "shop-api");
	}

	@SpringBootApplication
	static class TestApp {

		@Bean
		MeController meController() {
			return new MeController();
		}

		@Bean
		CoreSecurityCustomizer adminOnly() {
			return http -> http.authorizeHttpRequests(a -> a.requestMatchers("/api/admin/**").hasRole("ADMIN"));
		}
	}

	@RestController
	static class MeController {

		@GetMapping("/api/me")
		ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal JwtDto.Claims claims) {
			return ApiResponse.success(Map.of("memberId", claims.getSubject(), "roles", claims.getRoles()));
		}

		@GetMapping("/api/admin/stats")
		ApiResponse<String> stats() {
			return ApiResponse.success("ok");
		}
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@Test
	@DisplayName("Bearer 액세스 토큰 → 로그인 상태, 컨트롤러가 @AuthenticationPrincipal 로 claims 를 받는다")
	void authenticated() throws Exception {
		String token = JwtUtils.issueAccessToken("member-7", List.of("USER"), null);

		mockMvc.perform(get("/api/me").header("Authorization", bearer(token)))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data.memberId").value("member-7"))
			   .andExpect(jsonPath("$.data.roles[0]").value("USER"));
	}

	@Test
	@DisplayName("토큰 없음 C401, 위조 JWT-INVALID, 리프레시 토큰 JWT-TYPE — 모두 401 + WWW-Authenticate")
	void rejected() throws Exception {
		mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/api/me").header("Authorization", bearer("forged.token.value")))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("JWT-INVALID"))
			   .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
		String refresh = JwtUtils.issue("member-7", List.of("USER"), null).getRefreshToken();
		mockMvc.perform(get("/api/me").header("Authorization", bearer(refresh)))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("JWT-TYPE"));
	}

	@Test
	@DisplayName("roles → hasRole — USER 는 관리자 API 403, ADMIN 은 200")
	void roles() throws Exception {
		mockMvc.perform(get("/api/admin/stats").header("Authorization", bearer(JwtUtils.issueAccessToken("u", List.of("USER"), null))))
			   .andExpect(status().isForbidden())
			   .andExpect(jsonPath("$.code").value("C403"));
		mockMvc.perform(get("/api/admin/stats").header("Authorization", bearer(JwtUtils.issueAccessToken("a", List.of("ADMIN"), null))))
			   .andExpect(status().isOk());
	}

	@Test
	@DisplayName("기동 안내 — RS256 발급+검증, Bearer 필터 연동")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("JwtUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isTrue();
		assertThat(report.getDetail()).contains("RS256 발급+검증", "iss=shop-api", "access 30분 / refresh 14일", "Bearer 필터 연동");
	}
}
