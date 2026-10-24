package com.corelib.example.web;

import com.corelib.lang.StringUtils;
import com.corelib.web.CoreSecurityCustomizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 예제 앱 진입점 — @Async 로 접수 알림을 보내 traceId 가 다른 스레드로 넘어가는 것을 보여준다. @Scheduled 는 IP 별 접수 기록 정리용 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class WebExampleApplication {

	private static final String ADMIN_KEY_HEADER = "X-Admin-Key";

	public static void main(String[] args) {
		SpringApplication.run(WebExampleApplication.class, args);
	}

	/**
	 * core 기본 보안 체인 확장 — /api/admin/** 는 ADMIN 만, X-Admin-Key 헤더로 인증.
	 * 실서비스는 이 자리에 jwt 인증 필터를 끼운다 (core 체인의 공개 경로·401/403 JSON 은 그대로 쓴다).
	 */
	@Bean
	CoreSecurityCustomizer adminSecurity(@Value("${example.admin.api-key:}") String adminApiKey) {
		return http -> http
			.addFilterBefore(new AdminKeyFilter(adminApiKey), UsernamePasswordAuthenticationFilter.class)
			.authorizeHttpRequests(authorize -> authorize.requestMatchers("/api/admin/**").hasRole("ADMIN"));
	}

	/** 키가 맞으면 ADMIN 으로 인증 — 비교는 상수 시간, 키를 설정하지 않았으면 아무도 통과하지 못한다 */
	static class AdminKeyFilter extends OncePerRequestFilter {

		private final byte[] adminApiKey;

		AdminKeyFilter(String adminApiKey) {
			this.adminApiKey = StringUtils.isBlank(adminApiKey) ? null : adminApiKey.trim().getBytes(StandardCharsets.UTF_8);
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String key = request.getHeader(ADMIN_KEY_HEADER);
			if (adminApiKey != null && key != null && MessageDigest.isEqual(adminApiKey, key.getBytes(StandardCharsets.UTF_8))) {
				SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
					"admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
			}
			try {
				chain.doFilter(request, response);
			} finally {
				SecurityContextHolder.clearContext();
			}
		}
	}
}
