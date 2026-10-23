package com.corelib.web;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * web 모듈 기본 설정 — 가장 낮은 우선순위로 넣어 소비 앱 yml 에 같은 키가 있으면 그쪽이 이긴다.
 * <ul>
 *   <li>로그 줄에 traceId: logging.pattern.level = "%5p [%X{traceId:-}]"</li>
 *   <li>Actuator 는 health·info 만 웹에 노출, 상세(DB·디스크 등)는 숨김, 쿠버네티스 probe(/actuator/health/liveness·readiness) 켬</li>
 * </ul>
 */
public class WebDefaults implements EnvironmentPostProcessor {

	private static final String SOURCE_NAME = "coreWebDefaults";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.getPropertySources().contains(SOURCE_NAME)) {
			return;
		}
		environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, Map.of(
			"logging.pattern.level", "%5p [%X{" + TraceIdFilter.MDC_KEY + ":-}]",
			"management.endpoints.web.exposure.include", "health,info",
			"management.endpoint.health.show-details", "never",
			"management.endpoint.health.probes.enabled", "true")));
	}
}
