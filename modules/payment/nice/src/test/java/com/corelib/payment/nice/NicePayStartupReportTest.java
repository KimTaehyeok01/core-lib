package com.corelib.payment.nice;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NicePayStartupReportTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(NicePayInitialize.class, NicePayStartupReport.class));

	@Test
	@DisplayName("키 있음 - 초기화 완료, 샌드박스·주소·timeout, 시크릿 키 원문은 없음")
	void ready() {
		runner.withPropertyValues("payment.nice.client-key=S2_client", "payment.nice.secret-key=secret-value",
				  "payment.nice.base-url=https://sandbox-api.nicepay.co.kr")
			  .run(context -> {
				  StartupReport report = context.getBean(NicePayStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("샌드박스", "https://sandbox-api.nicepay.co.kr", "timeout=5s/30s")
												.doesNotContain("secret-value");
			  });
	}

	@Test
	@DisplayName("base-url 빈 값 - 운영 기본 호스트로 초기화하고 그 주소를 안내")
	void ready_blankBaseUrl() {
		runner.withPropertyValues("payment.nice.client-key=R2_client", "payment.nice.secret-key=secret-value", "payment.nice.base-url=")
			  .run(context -> {
				  StartupReport report = context.getBean(NicePayStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("운영", "base-url=" + NicePayProperty.DEFAULT_BASE_URL);
			  });
	}

	@Test
	@DisplayName("client-key 없음 - 미초기화, 넣어야 할 설정 이름 안내")
	void notReady() {
		runner.run(context -> {
			StartupReport report = context.getBean(NicePayStartupReport.class).report();
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("payment.nice.client-key");
		});
	}

	@Test
	@DisplayName("secret-key 누락·base-url 이 https 아님 - 기동 실패, 설정 이름을 담은 메시지")
	void invalid() {
		runner.withPropertyValues("payment.nice.client-key=S2_client")
			  .run(context -> assertThat(context).hasFailed()
												 .getFailure().rootCause().hasMessageContaining("payment.nice.client-key / secret-key"));
		runner.withPropertyValues("payment.nice.client-key=S2_client", "payment.nice.secret-key=secret-value",
				  "payment.nice.base-url=http://sandbox-api.nicepay.co.kr")
			  .run(context -> assertThat(context).hasFailed()
												 .getFailure().rootCause().hasMessageContaining("payment.nice.base-url"));
	}
}
