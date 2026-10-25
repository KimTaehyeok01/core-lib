package com.corelib.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.response.ApiResponse;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import com.corelib.crypto.PemUtils;
import com.corelib.crypto.RsaUtils;
import com.corelib.web.CoreSecurityCustomizer;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = JwtSecurityIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class JwtSecurityIntegrationTest {

	private static final KeyPair KEY_PAIR = RsaUtils.generateKeyPair(2048);
	private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("core.jwt.private-key", () -> PemUtils.writePrivateKey(KEY_PAIR.getPrivate()));
		registry.add("core.jwt.issuer", () -> "shop-api");
		registry.add("core.web.security.permit-paths", () -> "/api/auth/**,/api/public/**");
	}

	@SpringBootApplication
	@EnableMethodSecurity
	static class TestApp {

		@Bean
		MeController meController() {
			return new MeController();
		}

		@Bean
		ReportController reportController() {
			return new ReportController();
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

		/** 공개 경로 — 만료된 액세스 토큰을 단 채로 와도 처리돼야 한다 */
		@PostMapping("/api/auth/refresh")
		ApiResponse<String> refresh() {
			return ApiResponse.success("refreshed");
		}
	}

	/** 공개 경로지만 메서드 보안(@PreAuthorize)이 막는다 — 거부는 SecurityExceptionHandler 가 응답 */
	@RestController
	static class ReportController {

		@PreAuthorize("hasRole('ADMIN')")
		@GetMapping("/api/public/reports")
		public ApiResponse<String> reports() {
			return ApiResponse.success("reports");
		}
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	/** 같은 키로 서명했지만 이미 만료된(시계 오차 30초 초과) 액세스 토큰 */
	private static String expiredAccessToken() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("shop-api").subject("member-7")
														 .expirationTime(Date.from(Instant.now().minusSeconds(120)))
														 .claim("token_type", "ACCESS").claim("roles", List.of("ADMIN")).build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
		jwt.sign(new RSASSASigner(KEY_PAIR.getPrivate()));
		return jwt.serialize();
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
	@DisplayName("보호 경로 — 토큰 없음 C401, 만료 JWT-EXPIRED, 위조 JWT-INVALID, 리프레시 토큰 JWT-TYPE (토큰 오류는 401 + WWW-Authenticate)")
	void rejected() throws Exception {
		mockMvc.perform(get("/api/me"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"))
			   .andExpect(header().doesNotExist("WWW-Authenticate"));
		mockMvc.perform(get("/api/me").header("Authorization", bearer(expiredAccessToken())))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("JWT-EXPIRED"))
			   .andExpect(header().string("WWW-Authenticate", INVALID_TOKEN_CHALLENGE));
		mockMvc.perform(get("/api/me").header("Authorization", bearer("forged.token.value")))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("JWT-INVALID"))
			   .andExpect(header().string("WWW-Authenticate", INVALID_TOKEN_CHALLENGE));
		String refresh = JwtUtils.issue("member-7", List.of("USER"), null).getRefreshToken();
		mockMvc.perform(get("/api/me").header("Authorization", bearer(refresh)))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("JWT-TYPE"))
			   .andExpect(header().string("WWW-Authenticate", INVALID_TOKEN_CHALLENGE));
	}

	@Test
	@DisplayName("공개 경로 — 만료·위조·리프레시 토큰이 붙어 있어도 무시하고 정상 처리 (만료 토큰을 단 채 갱신 API)")
	void publicPathIgnoresBadToken() throws Exception {
		mockMvc.perform(post("/api/auth/refresh").header("Authorization", bearer(expiredAccessToken())))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data").value("refreshed"))
			   .andExpect(header().doesNotExist("WWW-Authenticate"));
		mockMvc.perform(post("/api/auth/refresh").header("Authorization", bearer("forged.token.value"))).andExpect(status().isOk());
		String refresh = JwtUtils.issue("member-7", List.of("USER"), null).getRefreshToken();
		mockMvc.perform(post("/api/auth/refresh").header("Authorization", bearer(refresh))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("@PreAuthorize 거부 — 토큰 없음 C401, 만료 토큰 JWT-EXPIRED 401 + WWW-Authenticate, USER 403, ADMIN 200")
	void preAuthorize() throws Exception {
		mockMvc.perform(get("/api/public/reports"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/api/public/reports").header("Authorization", bearer(expiredAccessToken())))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("JWT-EXPIRED"))
			   .andExpect(header().string("WWW-Authenticate", INVALID_TOKEN_CHALLENGE));
		mockMvc.perform(get("/api/public/reports").header("Authorization", bearer(JwtUtils.issueAccessToken("u", List.of("USER"), null))))
			   .andExpect(status().isForbidden())
			   .andExpect(jsonPath("$.code").value("C403"));
		mockMvc.perform(get("/api/public/reports").header("Authorization", bearer(JwtUtils.issueAccessToken("a", List.of("ADMIN"), null))))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data").value("reports"));
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
	@DisplayName("기동 안내 — RS256 발급+검증, core 보안 체인에 Bearer 필터 연동")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("JwtUtils")).findFirst().orElseThrow();
		assertThat(report.isReady()).isTrue();
		assertThat(report.getDetail()).contains("RS256 발급+검증", "iss=shop-api", "access 30분 / refresh 14일", "Bearer 필터 연동");
	}
}
