package com.corelib.auth.nice;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NiceAuthStartupReportTest {

	private static final String CLIENT_SECRET = "ns-test-value";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(NiceAuthInitialize.class, NiceAuthStartupReport.class));

	private final ApplicationContextRunner configured = runner.withPropertyValues("auth.nice.client-id=nice-client",
		"auth.nice.client-secret=" + CLIENT_SECRET, "auth.nice.return-url=https://example.com/nice/return");

	private static StartupReport report(AssertableApplicationContext context) {
		return context.getBean(NiceAuthStartupReport.class).report();
	}

	/** 원인 사슬 전체의 메시지 — 설정값이 어느 단계에도 새지 않았는지 본다 */
	private static String failureMessages(AssertableApplicationContext context) {
		StringBuilder out = new StringBuilder();
		for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
			out.append(cause.getMessage()).append('\n');
		}
		return out.toString();
	}

	@Test
	@DisplayName("client-id 없음 - 자동설정 꺼짐, 미초기화 안내")
	void notConfigured() {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(NiceAuthInitialize.class);
			StartupReport report = report(context);
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("auth.nice.client-id");
		});
	}

	@Test
	@DisplayName("정상 설정 - 초기화 완료, 인증수단·return-url·base-url 안내, client-secret 원문은 없음")
	void ready() {
		configured.run(context -> {
			StartupReport report = report(context);
			assertThat(report.isReady()).isTrue();
			assertThat(report.getDetail()).contains("[M]", "https://example.com/nice/return", NiceAuthProperty.DEFAULT_BASE_URL)
										  .doesNotContain(CLIENT_SECRET);
		});
	}

	@Test
	@DisplayName("client-id 있음 + base-url 이 외부 http - 기동 실패, 메시지에 키 이름만 (URL·시크릿 값 없음)")
	void invalidBaseUrlFailsStartup() {
		configured.withPropertyValues("auth.nice.base-url=http://proxy.example.com")
				  .run(context -> {
					  assertThat(context).hasFailed();
					  assertThat(failureMessages(context)).contains("auth.nice.base-url")
														  .doesNotContain("proxy.example.com", CLIENT_SECRET);
				  });
	}

	@Test
	@DisplayName("client-id 있음 + client-secret 없음 - 기동 실패 (첫 호출 500 대신)")
	void missingSecretFailsStartup() {
		runner.withPropertyValues("auth.nice.client-id=nice-client", "auth.nice.return-url=https://example.com/nice/return")
			  .run(context -> {
				  assertThat(context).hasFailed();
				  assertThat(failureMessages(context)).contains("client-secret");
			  });
	}

	@Test
	@DisplayName("기동 뒤 설정이 잘못 바뀜(tx-ttl 30분) - ready 대신 notReady, 키 이름 안내")
	void invalidAfterStartup() {
		configured.run(context -> {
			context.getBean(NiceAuthProperty.class).setTxTtl(Duration.ofMinutes(30));

			StartupReport report = report(context);
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("auth.nice.tx-ttl");
		});
	}
}
