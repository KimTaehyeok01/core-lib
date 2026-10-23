package com.corelib.jwt;

import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization: Bearer {액세스 토큰} 검증 → 로그인 상태(SecurityContext).
 * <ul>
 *   <li>헤더가 없으면 그냥 통과 — 공개 경로는 그대로, 인증 경로는 core 체인이 C401</li>
 *   <li>토큰을 보냈는데 만료·위조·리프레시 토큰이면 바로 401 + JWT-EXPIRED·JWT-INVALID·JWT-TYPE — 클라이언트가 갱신할지 판단한다</li>
 *   <li>principal = {@link JwtDto.Claims} — 컨트롤러에서 {@code @AuthenticationPrincipal JwtDto.Claims claims}</li>
 *   <li>roles 의 각 값은 ROLE_ 접두 권한이 된다 (ADMIN → ROLE_ADMIN, hasRole("ADMIN"))</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";
	private static final String ROLE_PREFIX = "ROLE_";

	private final ObjectMapper objectMapper;

	public JwtAuthenticationFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			chain.doFilter(request, response);
			return;
		}
		JwtDto.Claims claims;
		try {
			claims = JwtUtils.verifyAccessToken(header.substring(BEARER.length()));
		} catch (CoreException e) {
			reject(response, e.getErrorCode());
			return;
		}
		List<GrantedAuthority> authorities = claims.getRoles().stream()
			.map(role -> role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role)
			.<GrantedAuthority>map(SimpleGrantedAuthority::new)
			.toList();
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(claims, null, authorities));
		try {
			chain.doFilter(request, response);
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private void reject(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		response.setStatus(errorCode.status());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode.code(), errorCode.message()));
	}
}
