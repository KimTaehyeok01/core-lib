// 기동 안내 보고 — 키가 있으면 초기화 완료(키 종류·주소, 키 원문 없음), 없으면 미초기화와 필요한 설정을 알리는지 검증
package com.corelib.payment.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TossPayStartupReportTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TossPayInitialize.class, TossPayStartupReport.class));

	@Test
	@DisplayName("시크릿 키 있음 - 초기화 완료, 테스트 키·기본 주소·timeout, 키 원문은 없음")
	void ready() {
		runner.withPropertyValues("payment.toss.secret-key=test_sk_secret-value")
			  .run(context -> {
				  StartupReport report = context.getBean(TossPayStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("테스트 키", "https://api.tosspayments.com", "timeout=")
												.doesNotContain("secret-value");
			  });
	}

	@Test
	@DisplayName("시크릿 키 없음 - 미초기화, 넣어야 할 설정 이름 안내")
	void notReady() {
		runner.run(context -> {
			StartupReport report = context.getBean(TossPayStartupReport.class).report();
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("payment.toss.secret-key");
		});
	}
}
