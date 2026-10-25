package com.corelib.web;

import com.corelib.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 테스트용 인증 필터 — X-Test-Role 헤더가 있으면 그 역할로 로그인, X-Test-Auth-Error 헤더가 있으면
 * 토큰 필터처럼 인증 실패 사유만 {@link WebSecurityInitialize#AUTH_ERROR_ATTRIBUTE} 에 남기고 통과시킨다.
 */
class TestAuthenticationFilter extends OncePerRequestFilter {

	static final String ROLE_HEADER = "X-Test-Role";
	static final String AUTH_ERROR_HEADER = "X-Test-Auth-Error";
	static final ErrorCode EXPIRED = ErrorCode.of("TEST-EXPIRED", "토큰이 만료되었습니다.", 401);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String role = request.getHeader(ROLE_HEADER);
		if (role != null) {
			SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				"tester", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
		}
		if (request.getHeader(AUTH_ERROR_HEADER) != null) {
			request.setAttribute(WebSecurityInitialize.AUTH_ERROR_ATTRIBUTE, EXPIRED);
		}
		try {
			chain.doFilter(request, response);
		} finally {
			SecurityContextHolder.clearContext();
		}
	}
}
