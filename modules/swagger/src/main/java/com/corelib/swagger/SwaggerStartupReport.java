// 기동 안내에 Swagger UI 주소를 알리는 자동설정
package com.corelib.swagger;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import com.corelib.lang.StringUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

/** springdoc.swagger-ui.enabled=false(운영에서 문서 숨김)면 주소도 찍지 않는다 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "springdoc.swagger-ui", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SwaggerStartupReport implements StartupReporter {

	private static final String DEFAULT_PATH = "/swagger-ui.html";

	private final Environment environment;

	public SwaggerStartupReport(Environment environment) {
		this.environment = environment;
	}

	@Override
	public StartupReport report() {
		return StartupReport.link("Swagger", StringUtils.defaultIfBlank(environment.getProperty("springdoc.swagger-ui.path"), DEFAULT_PATH));
	}
}
