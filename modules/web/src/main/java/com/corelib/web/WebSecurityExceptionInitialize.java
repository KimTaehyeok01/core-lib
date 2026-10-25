package com.corelib.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;

/**
 * 컨트롤러의 권한 예외를 401·403 JSON 으로 바꾸는 {@link SecurityExceptionHandler} 를 등록한다.
 * core 보안 체인(core.web.security.enabled)과 따로 둔다 — 체인을 끄고 직접 만든 앱도 @PreAuthorize 거부가 500 이 되지 않게.
 */
@AutoConfiguration
@ConditionalOnClass(AccessDeniedException.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSecurityExceptionInitialize {

	@Bean
	@ConditionalOnMissingBean
	public SecurityExceptionHandler coreSecurityExceptionHandler() {
		return new SecurityExceptionHandler();
	}
}
