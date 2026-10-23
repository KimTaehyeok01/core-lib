package com.corelib.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.exception.CoreExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CommonAutoConfigurationTest {

	@Test
	@DisplayName("서블릿 웹 앱이면 CoreExceptionHandler 자동 등록")
	void registersHandlerInWebApp() {
		new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class))
			.run(context -> assertThat(context).hasSingleBean(CoreExceptionHandler.class));
	}

	@Test
	@DisplayName("웹 앱이 아니면 등록하지 않음")
	void skipsInNonWebApp() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class))
			.run(context -> assertThat(context).doesNotHaveBean(CoreExceptionHandler.class));
	}
}
