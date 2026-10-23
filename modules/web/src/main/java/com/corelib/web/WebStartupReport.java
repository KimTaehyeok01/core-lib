package com.corelib.web;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import com.corelib.lang.StringUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/** 기동 안내에 헬스체크 주소·요청 로그·보안 체인 상태를 알린다 */
@AutoConfiguration(after = {WebInitialize.class, WebSecurityInitialize.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebStartupReport {

	private static final String SECURITY_CHAIN_CLASS = "org.springframework.security.web.SecurityFilterChain";

	/** 관리 포트를 따로 쓰면(management.server.port) 앱 주소 뒤에 붙일 수 없어 찍지 않는다 */
	@Bean
	public StartupReporter coreHealthStartupReport(Environment environment) {
		return () -> StringUtils.isNotBlank(environment.getProperty("management.server.port"))
			? StartupReport.ready("Health", "관리 포트 " + environment.getProperty("management.server.port") + " 의 /actuator/health")
			: StartupReport.link("Health", StringUtils.defaultIfBlank(environment.getProperty("management.endpoints.web.base-path"), "/actuator") + "/health");
	}

	@Bean
	public StartupReporter coreWebStartupReport(ObjectProvider<WebProperty> property) {
		return () -> {
			WebProperty value = property.getIfAvailable();
			if (value == null) {
				return StartupReport.notReady("Web", "서블릿 웹 앱이 아닙니다");
			}
			WebProperty.RequestLog requestLog = value.getRequestLog();
			String log = !requestLog.isEnabled() ? "요청 로그 꺼짐" : requestLog.isIncludeBody() ? "요청 로그(본문 포함·마스킹)" : "요청 로그";
			return StartupReport.ready("Web", "traceId 헤더=" + value.getTrace().getHeader() + ", " + log
				+ ", 신뢰 프록시 " + value.getClientIp().getTrustedProxies().size() + "개");
		};
	}

	@Bean
	public StartupReporter coreSecurityStartupReport(ListableBeanFactory beanFactory, ObjectProvider<WebProperty> property) {
		return () -> {
			if (!ClassUtils.isPresent(SECURITY_CHAIN_CLASS, WebStartupReport.class.getClassLoader())) {
				return StartupReport.ready("Security", "미사용 (spring-boot-starter-security 없음)");
			}
			if (!beanFactory.containsBean(WebSecurityInitialize.CHAIN_BEAN)) {
				return StartupReport.ready("Security", "앱이 직접 만든 보안 체인 사용");
			}
			WebProperty.Security security = property.getObject().getSecurity();
			String access = security.getDefaultAccess() == WebProperty.DefaultAccess.AUTHENTICATED ? "인증 필요" : "모두 허용";
			String cors = security.getCorsAllowedOrigins().isEmpty() ? "CORS 없음" : "CORS " + security.getCorsAllowedOrigins();
			return StartupReport.ready("Security", "core 기본 체인 — 기본 접근=" + access + ", 추가 공개 경로 "
				+ security.getPermitPaths().size() + "개, " + cors);
		};
	}
}
