package com.corelib.common.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class StartupLogPrinterTest {

	@Test
	@DisplayName("로고·앱 이름·프로필·Local/Network 주소·화면 경로·모듈 초기화 결과를 한 덩어리로")
	void render() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.application.name", "shop-api")
			.withProperty("server.servlet.context-path", "/shop");
		environment.setActiveProfiles("local");
		StartupLogPrinter printer = new StartupLogPrinter(List.of(
			() -> StartupReport.link("Swagger", "/swagger-ui.html"),
			() -> StartupReport.ready("TossPayUtils", "base-url=https://api.tosspayments.com"),
			() -> StartupReport.notReady("NicePayUtils", "payment.nice.client-key 미설정")));

		String out = printer.render(environment, 8080, "192.168.0.12");

		assertThat(out).contains("____", "shop-api 실행 완료 (profile: local)",
			"Local   : http://localhost:8080/shop",
			"Network : http://192.168.0.12:8080/shop",
			"Swagger : http://localhost:8080/shop/swagger-ui.html",
			"[core] TossPayUtils 초기화 완료 — base-url=https://api.tosspayments.com",
			"[core] NicePayUtils 미초기화 — payment.nice.client-key 미설정");
	}

	@Test
	@DisplayName("웹 앱이 아니면 주소 줄 없이 모듈 결과만")
	void renderWithoutWebServer() {
		String out = new StartupLogPrinter(List.of(() -> StartupReport.ready("MailUtils", null)))
			.render(new MockEnvironment(), null, null);

		assertThat(out).contains("application 실행 완료 (profile: default)", "[core] MailUtils 초기화 완료")
					   .doesNotContain("Local", "Network");
	}

	@Test
	@DisplayName("core.startup-log.enabled=false 면 출력기를 등록하지 않음")
	void toggle() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(StartupLogAutoConfiguration.class));

		runner.run(context -> assertThat(context).hasSingleBean(StartupLogPrinter.class));
		runner.withPropertyValues("core.startup-log.enabled=false")
			  .run(context -> assertThat(context).doesNotHaveBean(StartupLogPrinter.class));
	}
}
