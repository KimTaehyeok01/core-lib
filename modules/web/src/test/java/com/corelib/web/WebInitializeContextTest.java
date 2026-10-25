package com.corelib.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;

/** 자동 구성 조건 — 설정 없음·있음·잘못됨, 서블릿 웹 앱이 아닐 때 */
class WebInitializeContextTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(WebInitialize.class, WebSecurityExceptionInitialize.class, WebStartupReport.class));

	@Test
	@DisplayName("설정 없음 — 필터·MDC 데코레이터·SecurityExceptionHandler·기동 안내 등록")
	void defaults() {
		runner.run(context -> {
			assertThat(context).hasBean("coreTraceIdFilter").hasBean("coreRequestLogFilter").hasBean("coreMdcTaskDecorator");
			assertThat(context).hasSingleBean(SecurityExceptionHandler.class);
			assertThat(context.getBeansOfType(StartupReporter.class)).containsKeys("coreWebStartupReport", "coreSecurityStartupReport");
			assertThat(context.getBean(WebProperty.class).getSecurity().isPublicDocs()).isFalse();
		});
	}

	@Test
	@DisplayName("설정 있음 — 요청 로그 끄기, security.enabled=false 여도 SecurityExceptionHandler 는 남음")
	void configured() {
		runner.withPropertyValues("core.web.request-log.enabled=false", "core.web.security.enabled=false", "core.web.security.public-docs=true")
			  .run(context -> {
				  assertThat(context).doesNotHaveBean("coreRequestLogFilter");
				  assertThat(context).hasSingleBean(SecurityExceptionHandler.class);
				  assertThat(context.getBean(WebProperty.class).getSecurity().isPublicDocs()).isTrue();
			  });
	}

	@Test
	@DisplayName("설정 잘못됨 — default-access 에 없는 값이면 기동 실패")
	void invalid() {
		runner.withPropertyValues("core.web.security.default-access=EVERYONE").run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("앱이 SecurityExceptionHandler 를 직접 만들면 core 것은 물러난다")
	void customHandler() {
		SecurityExceptionHandler custom = new SecurityExceptionHandler();
		runner.withBean("appSecurityExceptionHandler", SecurityExceptionHandler.class, () -> custom)
			  .run(context -> assertThat(context).hasSingleBean(SecurityExceptionHandler.class)
												 .getBean(SecurityExceptionHandler.class).isSameAs(custom));
	}

	@Test
	@DisplayName("Spring Security 가 없거나 서블릿 웹 앱이 아니면 등록하지 않는다")
	void notApplicable() {
		runner.withClassLoader(new FilteredClassLoader(AccessDeniedException.class))
			  .run(context -> assertThat(context).doesNotHaveBean(SecurityExceptionHandler.class).hasBean("coreRequestLogFilter"));
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(WebInitialize.class, WebSecurityExceptionInitialize.class, WebStartupReport.class))
			.run(context -> assertThat(context).doesNotHaveBean(SecurityExceptionHandler.class).doesNotHaveBean("coreRequestLogFilter"));
	}
}
