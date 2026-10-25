package com.corelib.auth.kcb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import com.corelib.common.startup.StartupReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import kcb.module.v3.OkCert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KcbAuthStartupReportTest {

	@TempDir
	Path tempDir;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(KcbAuthInitialize.class, KcbAuthStartupReport.class));

	private Path license;
	private ApplicationContextRunner configured;

	@BeforeEach
	void setUp() throws IOException {
		OkCert.reset();
		license = Files.writeString(tempDir.resolve("V00000000000_license.dat"), "license");
		configured = runner.withPropertyValues("auth.kcb.cp-cd=V00000000000", "auth.kcb.license-path=" + license,
			"auth.kcb.return-url=https://example.com/kcb/return", "auth.kcb.site-name=코어랩", "auth.kcb.site-url=example.com");
	}

	@AfterEach
	void tearDown() {
		OkCert.reset();
	}

	private static StartupReport report(AssertableApplicationContext context) {
		return context.getBean(KcbAuthStartupReport.class).report();
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
	@DisplayName("cp-cd 없음 - 자동설정 꺼짐, 미초기화 안내")
	void notConfigured() {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(KcbAuthInitialize.class);
			StartupReport report = report(context);
			assertThat(report.isReady()).isFalse();
			assertThat(report.getDetail()).contains("auth.kcb.cp-cd");
		});
	}

	@Test
	@DisplayName("정상 설정 - 초기화 완료, target·jar 유무·popup-url·timeout 안내, 라이선스 경로는 없음")
	void ready() {
		configured.run(context -> {
			StartupReport report = report(context);
			assertThat(report.isReady()).isTrue();
			assertThat(report.getDetail()).contains("target=PROD", "OkCert jar 있음", "popup-url=" + KcbAuthProperty.DEFAULT_POPUP_URL,
				"timeout=10s").doesNotContain(license.toString());
		});
	}

	@Test
	@DisplayName("popup-url 이 외부 http - 기동 실패, 메시지에 키 이름만 (URL 값 없음)")
	void httpPopupUrlFailsStartup() {
		configured.withPropertyValues("auth.kcb.popup-url=http://popup.example.com/CommonSvl")
				  .run(context -> {
					  assertThat(context).hasFailed();
					  assertThat(failureMessages(context)).contains("auth.kcb.popup-url").doesNotContain("popup.example.com");
				  });
	}

	@Test
	@DisplayName("return-url 형식 위반·timeout 0·라이선스 없음 - 각각 기동 실패, 라이선스 경로는 메시지에 없음")
	void invalidSettingsFailStartup() {
		configured.withPropertyValues("auth.kcb.return-url=javascript:alert(1)")
				  .run(context -> assertThat(failureMessages(context)).contains("auth.kcb.return-url"));
		configured.withPropertyValues("auth.kcb.timeout=0s")
				  .run(context -> assertThat(failureMessages(context)).contains("auth.kcb.timeout"));

		String missing = tempDir.resolve("missing.dat").toString();
		configured.withPropertyValues("auth.kcb.license-path=" + missing)
				  .run(context -> {
					  assertThat(context).hasFailed();
					  assertThat(failureMessages(context)).contains("auth.kcb.license-path").doesNotContain(missing);
				  });
	}

	@Test
	@DisplayName("컨텍스트 종료 - 호출 전용 스레드 풀도 닫혀 이후 호출은 EXTERNAL_ERROR")
	void shutdownOnClose() {
		configured.run(context -> assertThat(context).hasNotFailed());

		assertThatThrownBy(() -> KcbAuthUtils.start(null))
			.isInstanceOf(CoreException.class)
			.extracting(e -> ((CoreException) e).getErrorCode())
			.isEqualTo(KcbAuthError.EXTERNAL_ERROR);
		assertThat(OkCert.lastSvcName).isNull();
	}
}
