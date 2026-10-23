package com.corelib.swagger;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 스크롤을 줄이는 기본값 — 소비 앱 yml 에 같은 키를 적으면 그쪽이 이긴다(맨 뒤에 추가).
 * <ul>
 *   <li>springdoc.swagger-ui.doc-expansion=none — API 목록을 접힌 상태로 시작</li>
 *   <li>springdoc.swagger-ui.default-models-expand-depth=-1 — 맨 아래 Schemas 영역 숨김</li>
 * </ul>
 */
public class SwaggerUiDefaults implements EnvironmentPostProcessor {

	private static final String SOURCE_NAME = "coreSwaggerUiDefaults";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getPropertySources().contains(SOURCE_NAME)) {
			environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, Map.of(
				"springdoc.swagger-ui.doc-expansion", "none",
				"springdoc.swagger-ui.default-models-expand-depth", "-1")));
		}
	}
}
