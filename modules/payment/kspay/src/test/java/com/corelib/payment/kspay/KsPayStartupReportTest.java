package com.corelib.payment.kspay;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KsPayStartupReportTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(KsPayInitialize.class, KsPayStartupReport.class));

	@Test
	@DisplayName("mid·api-key 있음 - 초기화 완료, 승인·취소 주소·timeout, api-key 원문은 없음")
	void ready() {
		runner.withPropertyValues("payment.kspay.mid=2999199999", "payment.kspay.api-key=pgapi-secret-value",
				  "payment.kspay.webfep-base-url=" + KsPayProperty.DEV_WEBFEP_BASE_URL)
			  .run(context -> {
				  StartupReport report = context.getBean(KsPayStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("mid=2999199999", "승인=" + KsPayProperty.DEFAULT_APPROVAL_BASE_URL,
													  "취소=" + KsPayProperty.DEV_WEBFEP_BASE_URL, "취소 가능", "timeout=5s/30s")
												.doesNotContain("pgapi-secret-value");
			  });
	}

	@Test
	@DisplayName("base-url 빈 값 - 공식 기본 호스트로 초기화하고 그 주소를 안내, api-key 없으면 취소 불가 안내")
	void ready_blankBaseUrl() {
		runner.withPropertyValues("payment.kspay.mid=2999199999", "payment.kspay.approval-base-url=",
				  "payment.kspay.webfep-base-url= ")
			  .run(context -> {
				  assertThat(context).hasNotFailed();
				  StartupReport report = context.getBean(KsPayStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("승인=" + KsPayProperty.DEFAULT_APPROVAL_BASE_URL,
					  "취소=" + KsPayProperty.DEFAULT_WEBFEP_BASE_URL, "payment.kspay.api-key 미설정");
			  });
	}

	@Test
	@DisplayName("mid 없음 - 미초기화, 넣어야 할 설정 이름 안내")
	void notReady() {
		runner.run(context -> {
			StartupReport report = context.getBean(KsPayStartupReport.class).report();
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("payment.kspay.mid");
		});
	}

	@Test
	@DisplayName("base-url 이 https 아님 - 기동 실패, 어느 설정인지 키 이름을 담은 메시지")
	void invalid() {
		runner.withPropertyValues("payment.kspay.mid=2999199999", "payment.kspay.approval-base-url=http://kspay.ksnet.to")
			  .run(context -> assertThat(context).hasFailed()
												 .getFailure().rootCause().hasMessageContaining("payment.kspay.approval-base-url"));
		runner.withPropertyValues("payment.kspay.mid=2999199999", "payment.kspay.webfep-base-url=http://pay.ksnet.co.kr")
			  .run(context -> assertThat(context).hasFailed()
												 .getFailure().rootCause().hasMessageContaining("payment.kspay.webfep-base-url"));
	}
}
