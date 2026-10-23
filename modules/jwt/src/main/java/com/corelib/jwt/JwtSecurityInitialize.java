package com.corelib.jwt;

import com.corelib.web.CoreSecurityCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * web 모듈의 core 보안 체인에 Bearer 필터를 끼운다 — web 모듈·spring-boot-starter-security·jwt 키가 모두 있을 때만.
 * 필터는 빈으로 만들지 않는다 (빈으로 만들면 서블릿 필터로도 한 번 더 등록된다).
 */
@AutoConfiguration(after = JwtInitialize.class)
@ConditionalOnClass({CoreSecurityCustomizer.class, SecurityFilterChain.class})
@ConditionalOnBean(JwtInitialize.class)
@ConditionalOnProperty(prefix = "core.jwt.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JwtSecurityInitialize {

	/** core 보안 체인을 만들 때 찾는 빈 이름 — 기동 안내가 연동 여부를 알아본다 */
	static final String CUSTOMIZER_BEAN = "coreJwtSecurityCustomizer";

	@Bean(CUSTOMIZER_BEAN)
	public CoreSecurityCustomizer coreJwtSecurityCustomizer(ObjectMapper objectMapper) {
		return http -> http.addFilterBefore(new JwtAuthenticationFilter(objectMapper), UsernamePasswordAuthenticationFilter.class);
	}
}
