package com.corelib.web;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * core 기본 보안 체인에 끼워 넣는 확장 지점 — 빈으로 등록하면 체인을 만들 때 순서대로 호출된다.
 * <ul>
 *   <li>인증 필터 추가: {@code http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)}</li>
 *   <li>경로별 권한: {@code http.authorizeHttpRequests(a -> a.requestMatchers("/api/admin/**").hasRole("ADMIN"))}
 *       — core 의 기본 규칙(공개 경로·나머지 인증)보다 먼저 적용된다</li>
 * </ul>
 * 체인 전체를 직접 짜려면 SecurityFilterChain 빈을 만들면 core 기본 체인은 물러난다.
 */
@FunctionalInterface
public interface CoreSecurityCustomizer {

	void customize(HttpSecurity http) throws Exception;
}
