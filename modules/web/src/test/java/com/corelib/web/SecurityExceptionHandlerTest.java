package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** core 보안 체인을 끄고(security.enabled=false) 앱이 체인을 직접 만든 경우에도 @PreAuthorize 거부가 401·403 이 되는지 */
@SpringBootTest(classes = SecurityExceptionHandlerTest.TestApp.class, properties = "core.web.security.enabled=false")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class SecurityExceptionHandlerTest {

	private final MockMvc mockMvc;
	private final ApplicationContext context;

	@SpringBootApplication
	@EnableMethodSecurity
	static class TestApp {

		@Bean
		AdminController adminController() {
			return new AdminController();
		}

		/** 앱이 직접 만든 체인 — 전부 허용하고 권한은 @PreAuthorize 로만 */
		@Bean
		SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
			return http.csrf(AbstractHttpConfigurer::disable)
					   .addFilterBefore(new TestAuthenticationFilter(), AnonymousAuthenticationFilter.class)
					   .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
					   .build();
		}
	}

	@RestController
	static class AdminController {

		@GetMapping("/api/admin/stats")
		@PreAuthorize("hasRole('ADMIN')")
		ApiResponse<String> stats() {
			return ApiResponse.success("stats");
		}
	}

	@Test
	@DisplayName("core 체인 없음 — 그래도 SecurityExceptionHandler 는 등록된다")
	void registeredWithoutCoreChain() {
		assertThat(context.containsBean(WebSecurityInitialize.CHAIN_BEAN)).isFalse();
		assertThat(context.getBeansOfType(SecurityExceptionHandler.class)).hasSize(1);
	}

	@Test
	@DisplayName("@PreAuthorize 거부 — 익명 401 C401, 로그인했지만 권한 부족 403 C403, 권한 있으면 200")
	void preAuthorize() throws Exception {
		mockMvc.perform(get("/api/admin/stats"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(jsonPath("$.code").value("C401"));
		mockMvc.perform(get("/api/admin/stats").header(TestAuthenticationFilter.ROLE_HEADER, "USER"))
			   .andExpect(status().isForbidden())
			   .andExpect(jsonPath("$.code").value("C403"));
		mockMvc.perform(get("/api/admin/stats").header(TestAuthenticationFilter.ROLE_HEADER, "ADMIN"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.data").value("stats"));
	}

	@Test
	@DisplayName("@PreAuthorize 거부 + 인증 실패 사유 — 그 코드로 401 + WWW-Authenticate")
	void preAuthorizeWithAuthError() throws Exception {
		mockMvc.perform(get("/api/admin/stats").header(TestAuthenticationFilter.AUTH_ERROR_HEADER, "1"))
			   .andExpect(status().isUnauthorized())
			   .andExpect(header().string("WWW-Authenticate", SecurityExceptionHandler.INVALID_TOKEN_CHALLENGE))
			   .andExpect(jsonPath("$.code").value("TEST-EXPIRED"));
	}

	@Test
	@DisplayName("인증 정보 자체가 없을 때(익명 필터 없음)도 AccessDenied 는 401")
	void noAuthentication() {
		SecurityContextHolder.clearContext();

		ResponseEntity<ApiResponse<Void>> response = new SecurityExceptionHandler()
			.handleAccessDenied(new AccessDeniedException("거부"), new MockHttpServletRequest());

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().getCode()).isEqualTo("C401");
	}
}
