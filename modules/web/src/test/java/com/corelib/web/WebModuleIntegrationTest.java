package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.response.ApiResponse;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = WebModuleIntegrationTest.TestApp.class, properties = {
	"core.web.security.permit-paths=/api/public/**",
	"core.web.security.cors-allowed-origins=https://admin.example.com",
	"core.web.request-log.include-body=true"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class WebModuleIntegrationTest {

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	/** 모듈만 올리는 최소 앱 — 공개 API 하나, 인증 API 하나 */
	@SpringBootApplication
	static class TestApp {

		/** 테스트 클래스 안의 클래스는 컴포넌트 스캔에서 빠진다 — 빈으로 직접 등록 */
		@Bean
		TestController testController() {
			return new TestController();
		}

		/** 확장 지점 — /api/admin/** 는 ADMIN 만 (core 기본 규칙보다 먼저 적용) */
		@Bean
		CoreSecurityCustomizer adminOnly() {
			return http -> http.authorizeHttpRequests(authorize -> authorize.requestMatchers("/api/admin/**").hasRole("ADMIN"));
		}

		/** 테스트 인증 필터 — X-Test-Role 로 로그인, X-Test-Auth-Error 로 인증 실패 사유만 남김 */
		@Bean
		CoreSecurityCustomizer testAuthentication() {
			return http -> http.addFilterBefore(new TestAuthenticationFilter(), AnonymousAuthenticationFilter.class);
		}
	}

	@RestController
	static class TestController {

		@GetMapping("/api/public/whoami")
		ApiResponse<Map<String, String>> whoami(HttpServletRequest request) {
			return ApiResponse.success(Map.of("ip", ClientIpUtils.resolve(request), "traceId", TraceIdFilter.current()));
		}

		@PostMapping("/api/public/echo")
		ApiResponse<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
			return ApiResponse.success(body);
		}

		@GetMapping("/api/orders")
		ApiResponse<String> orders() {
			return ApiResponse.success("orders");
		}

		@GetMapping("/api/public/denied")
		ApiResponse<String> denied() {
			throw new AccessDeniedException("권한 없음");
		}

		/** springdoc 대신 — 문서 경로가 열렸는지만 본다 */
		@GetMapping("/v3/api-docs")
		ApiResponse<String> apiDocs() {
			return ApiResponse.success("docs");
		}
	}

	@Test
	@DisplayName("traceId — 없으면 만들어 응답 헤더로, 앞단이 준 형식 맞는 값은 이어 쓰고, 위험한 값은 버린다")
	void traceId() throws Exception {
		mockMvc.perform(get("/api/public/whoami"))
			   .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{16}")));
		mockMvc.perform(get("/api/public/whoami").header("X-Request-Id", "gateway-trace-0001"))
			   .andExpect(header().string("X-Request-Id", "gateway-trace-0001"))
			   .andExpect(jsonPath("$.data.traceId").value("gateway-trace-0001"));
		mockMvc.perform(get("/api/public/whoami").header("X-Request-Id", "bad\r\ninjected"))
			   .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{16}")));
	}

	@Test
	@DisplayName("클라이언트 IP — 로컬 프록시(127.0.0.1) 뒤에서 X-Forwarded-For 를 따른다")
	void clientIp() throws Exception {
		mockMvc.perform(get("/api/public/whoami").header("X-Forwarded-For", "203.0.113.7"))
			   .andExpect(jsonPath("$.data.ip").value("203.0.113.7"));
	}

	@Test
	@DisplayName("요청 로그 — 한 줄(메서드·경로·상태·시간·IP·traceId), 본문 민감값 마스킹, 쿼리스트링·헬스체크 제외")
	void requestLog(CapturedOutput output) throws Exception {
		mockMvc.perform(post("/api/public/echo?token=secret-query").header("X-Request-Id", "log-trace-0001")
											   .header("X-Forwarded-For", "203.0.113.7")
											   .contentType(MediaType.APPLICATION_JSON)
											   .content("{\"name\":\"홍길동\",\"password\":\"p@ss\"}"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data.password").value("p@ss"));    // 응답 자체는 그대로 나간다
		mockMvc.perform(get("/actuator/health"));

		String log = output.getOut();
		assertThat(log).containsPattern("\\[log-trace-0001\\].*POST /api/public/echo 200 \\d+ms ip=203\\.0\\.113\\.7");
		assertThat(log).contains("\"password\":\"***\"").doesNotContain("p@ss", "secret-query", "GET /actuator/health");
	}

	@Test
	@DisplayName("요청 로그 — body-max-length 를 넘은 본문은 경계에서 잘린 password 도 찍지 않고 크기만")
	void requestLogTruncated(CapturedOutput output) throws Exception {
		String json = "{\"filler\":\"" + "x".repeat(1965) + "\",\"password\":\"secret-password-123\"}";
		mockMvc.perform(post("/api/public/echo").contentType(MediaType.APPLICATION_JSON).content(json))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data.password").value("secret-password-123"));

		assertThat(output.getOut()).contains("POST /api/public/echo 200", "req=(" + json.length() + " bytes) res=(")
								   .doesNotContain("secret-pa");
	}

	@Test
	@DisplayName("Security — 공개 경로 통과, 나머지 401 JSON, 권한 부족 403 JSON, 컨트롤러 AccessDenied 는 익명 401·로그인 403, 헬스체크만 공개")
	void security() throws Exception {
		mockMvc.perform(get("/api/public/whoami")).andExpect(status().isOk());
		mockMvc.perform(get("/api/orders"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"))
			   .andExpect(jsonPath("$.success").value(false));
		mockMvc.perform(get("/api/admin/users"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/api/admin/users").header(TestAuthenticationFilter.ROLE_HEADER, "USER"))
			   .andExpect(status().isForbidden())
			   .andExpect(jsonPath("$.code").value("C403"));
		mockMvc.perform(get("/api/public/denied"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/api/public/denied").header(TestAuthenticationFilter.ROLE_HEADER, "USER"))
			   .andExpect(status().isForbidden())
			   .andExpect(jsonPath("$.code").value("C403"));
		mockMvc.perform(get("/actuator/health"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.status").value("UP"))
			   .andExpect(jsonPath("$.components").doesNotExist());
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("API 문서·actuator/info — public-docs 기본(false)이면 인증 필요")
	void docsClosedByDefault() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("인증 실패 사유 — 필터가 AUTH_ERROR_ATTRIBUTE 를 남기면 보호 경로는 그 코드로 401 + WWW-Authenticate, 공개 경로는 정상")
	void authErrorAttribute() throws Exception {
		mockMvc.perform(get("/api/orders").header(TestAuthenticationFilter.AUTH_ERROR_HEADER, "1"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
			   .andExpect(jsonPath("$.code").value("TEST-EXPIRED"))
			   .andExpect(jsonPath("$.message").value("토큰이 만료되었습니다."));
		mockMvc.perform(get("/api/public/denied").header(TestAuthenticationFilter.AUTH_ERROR_HEADER, "1"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("TEST-EXPIRED"));
		mockMvc.perform(get("/api/public/whoami").header(TestAuthenticationFilter.AUTH_ERROR_HEADER, "1"))
			   .andExpect(status().isOk());
		mockMvc.perform(get("/api/orders"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(header().doesNotExist("WWW-Authenticate"))
			   .andExpect(jsonPath("$.code").value("C401"));
	}

	@Test
	@DisplayName("CORS — 허용한 출처만 preflight 통과")
	void cors() throws Exception {
		mockMvc.perform(options("/api/public/whoami").header("Origin", "https://admin.example.com")
													 .header("Access-Control-Request-Method", "GET"))
			   .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.com"));
		mockMvc.perform(options("/api/public/whoami").header("Origin", "https://evil.example.com")
													 .header("Access-Control-Request-Method", "GET"))
			   .andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("기동 안내 — Health 주소, Web·Security 상태")
	void startupReport() {
		Map<String, StartupReport> reports = startupReporters.stream().map(StartupReporter::report)
															 .collect(java.util.stream.Collectors.toMap(StartupReport::getName, r -> r, (a, b) -> a));
		assertThat(reports.get("Health").getPath()).isEqualTo("/actuator/health");
		assertThat(reports.get("Web").getDetail()).contains("traceId 헤더=X-Request-Id", "본문 포함");
		assertThat(reports.get("Security").getDetail())
			.contains("core 기본 체인", "인증 필요", "추가 공개 경로 1개", "API 문서 비공개", "https://admin.example.com");
	}
}
