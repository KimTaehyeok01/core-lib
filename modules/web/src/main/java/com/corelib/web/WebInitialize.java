package com.corelib.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * 서블릿 웹 앱이면 traceId·요청 로그 필터와 비동기 MDC 전달을 등록하고 ClientIpUtils 를 초기화한다.
 * 필터 순서: traceId(가장 먼저) → 요청 로그 → Spring Security → 컨트롤러.
 */
@AutoConfiguration(before = TaskExecutionAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebProperty.class)
public class WebInitialize {

	/** Spring Security 필터(-100)보다 앞 */
	static final int TRACE_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
	static final int REQUEST_LOG_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

	public WebInitialize(WebProperty property) {
		ClientIpUtils.initialize(property.getClientIp());
	}

	@Bean
	public FilterRegistrationBean<TraceIdFilter> coreTraceIdFilter(WebProperty property) {
		FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter(property.getTrace().getHeader()));
		registration.setOrder(TRACE_ORDER);
		return registration;
	}

	@Bean
	@ConditionalOnProperty(prefix = "core.web.request-log", name = "enabled", havingValue = "true", matchIfMissing = true)
	public FilterRegistrationBean<RequestLogFilter> coreRequestLogFilter(WebProperty property) {
		FilterRegistrationBean<RequestLogFilter> registration = new FilterRegistrationBean<>(new RequestLogFilter(property.getRequestLog()));
		registration.setOrder(REQUEST_LOG_ORDER);
		return registration;
	}

	/** Boot 가 만드는 기본 TaskExecutor(@Async)에 자동으로 붙는다 — 소비 앱이 TaskDecorator 를 직접 만들면 그쪽 우선 */
	@Bean
	@ConditionalOnMissingBean(TaskDecorator.class)
	public TaskDecorator coreMdcTaskDecorator() {
		return new MdcTaskDecorator();
	}
}
