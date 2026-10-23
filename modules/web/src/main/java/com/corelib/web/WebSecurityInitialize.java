package com.corelib.web;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * spring-boot-starter-security 가 있으면 API 서버용 기본 보안 체인을 만든다.
 * <ul>
 *   <li>세션 없음(STATELESS), CSRF·폼 로그인·HTTP Basic 끔 — 토큰 인증 API 기준</li>
 *   <li>헬스체크·Swagger·/error 는 공개, 나머지는 default-access(기본: 인증 필요)</li>
 *   <li>401·403 은 ApiResponse(C401·C403) JSON — 전역 예외 핸들러와 같은 모양</li>
 *   <li>CORS 는 cors-allowed-origins 를 적었을 때만 허용</li>
 *   <li>소비 앱이 SecurityFilterChain 을 직접 만들면 이 체인은 만들지 않는다</li>
 * </ul>
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}, after = WebInitialize.class)
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "core.web.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSecurityInitialize {

	/** 기동 안내가 core 체인인지 알아보는 빈 이름 */
	static final String CHAIN_BEAN = "coreSecurityFilterChain";
	static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
		"/error", "/actuator/health", "/actuator/health/**", "/actuator/info",
		"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**");

	private static final Logger log = LoggerFactory.getLogger(WebSecurityInitialize.class);

	@Bean(CHAIN_BEAN)
	@ConditionalOnMissingBean(SecurityFilterChain.class)
	public SecurityFilterChain coreSecurityFilterChain(HttpSecurity http, WebProperty property, ObjectMapper objectMapper,
													   ObjectProvider<CoreSecurityCustomizer> customizers) throws Exception {
		WebProperty.Security security = property.getSecurity();
		http.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint((request, response, e) -> write(response, objectMapper, CommonErrorCode.UNAUTHORIZED))
				.accessDeniedHandler((request, response, e) -> write(response, objectMapper, CommonErrorCode.FORBIDDEN)));

		if (security.getCorsAllowedOrigins().isEmpty()) {
			http.cors(AbstractHttpConfigurer::disable);
		} else {
			http.cors(cors -> cors.configurationSource(corsSource(security)));
		}

		for (CoreSecurityCustomizer customizer : customizers.orderedStream().toList()) {
			customizer.customize(http);
		}

		List<String> publicPaths = new ArrayList<>(DEFAULT_PUBLIC_PATHS);
		publicPaths.addAll(security.getPermitPaths());
		http.authorizeHttpRequests(authorize -> {
			authorize.requestMatchers(publicPaths.toArray(String[]::new)).permitAll();
			if (security.getDefaultAccess() == WebProperty.DefaultAccess.PERMIT_ALL) {
				authorize.anyRequest().permitAll();
			} else {
				authorize.anyRequest().authenticated();
			}
		});
		return http.build();
	}

	/**
	 * 사용자 저장소가 없는 API 서버에서 Boot 가 임시 계정·비밀번호를 만들어 로그에 찍는 것을 막는다.
	 * 로그인 사용자를 쓰는 앱은 자기 UserDetailsService·AuthenticationProvider 를 만들면 이건 물러난다.
	 */
	@Bean
	@ConditionalOnMissingBean({UserDetailsService.class, AuthenticationManager.class, AuthenticationProvider.class})
	public UserDetailsService coreNoUserDetailsService() {
		return username -> {
			throw new UsernameNotFoundException("사용자 저장소가 없습니다.");
		};
	}

	@Bean
	@ConditionalOnMissingBean
	public SecurityExceptionHandler coreSecurityExceptionHandler() {
		return new SecurityExceptionHandler();
	}

	private static UrlBasedCorsConfigurationSource corsSource(WebProperty.Security security) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(security.getCorsAllowedOrigins());
		configuration.setAllowedMethods(security.getCorsAllowedMethods());
		configuration.addAllowedHeader("*");
		configuration.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	private static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode) throws IOException {
		if (response.isCommitted()) {
			log.debug("응답이 이미 나가 {} 를 쓰지 못했습니다.", errorCode.code());
			return;
		}
		response.setStatus(errorCode.status());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode.code(), errorCode.message()));
	}
}
