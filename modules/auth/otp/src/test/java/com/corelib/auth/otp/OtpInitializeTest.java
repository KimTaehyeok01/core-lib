package com.corelib.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OtpInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OtpInitialize.class));

	@AfterEach
	void reset() {
		OtpUtils.initialize(new OtpProperty());
	}

	@Test
	@DisplayName("설정 없음 - 기본값(SHA1·6자리·30초)으로 초기화, 기동 안내 ready")
	void defaults() {
		runner.run(context -> {
			StartupReport report = context.getBean(StartupReporter.class).report();
			assertThat(report.isReady()).isTrue();
			assertThat(report.getDetail()).contains("issuer=core-lib", "SHA1 6자리 30초", "허용 ±1");
		});
	}

	@Test
	@DisplayName("설정 있음 - auth.otp.* 가 OtpUtils 에 반영")
	void configured() {
		runner.withPropertyValues("auth.otp.issuer=내 서비스", "auth.otp.digits=8")
			  .run(context -> {
				  assertThat(context.getBean(StartupReporter.class).report().getDetail()).contains("issuer=내 서비스", "8자리");
				  assertThat(OtpUtils.generateCode(OtpUtils.generateSecret(), Instant.now())).hasSize(8);
			  });
	}

	@Test
	@DisplayName("잘못된 설정(digits=9) - 기동 실패")
	void invalidFailsStartup() {
		runner.withPropertyValues("auth.otp.digits=9")
			  .run(context -> assertThat(context).hasFailed());
	}
}
