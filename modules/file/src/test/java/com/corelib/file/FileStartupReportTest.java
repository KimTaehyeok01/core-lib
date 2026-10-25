package com.corelib.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class FileStartupReportTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(FileInitialize.class, FileStartupReport.class));

	@Test
	@DisplayName("허용 확장자 설정 - 초기화 완료, 저장소·max-size, 경고 없음")
	void readyWithAllowedExtensions() {
		runner.withPropertyValues("core.file.allowed-extensions=png,pdf", "core.file.max-size=5MB")
			  .run(context -> {
				  StartupReport report = context.getBean(FileStartupReport.class).report();
				  assertThat(report.isReady()).isTrue();
				  assertThat(report.getDetail()).contains("local", "max-size=5MB").doesNotContain("allowed-extensions 지정 권장");
			  });
	}

	@Test
	@DisplayName("허용 확장자 미설정 - 초기화 완료지만 allowed-extensions 지정 권장 경고")
	void warnsWithoutAllowedExtensions() {
		runner.run(context -> {
			StartupReport report = context.getBean(FileStartupReport.class).report();
			assertThat(report.isReady()).isTrue();
			assertThat(report.getDetail()).contains("허용 확장자 미설정 — allowed-extensions 지정 권장");
		});
	}

	@Test
	@DisplayName("storage=s3 인데 AWS SDK 없음 - 미초기화, 넣어야 할 의존성 안내")
	void notReadyWithoutAwsSdk() {
		runner.withClassLoader(new FilteredClassLoader(S3Client.class))
			  .withPropertyValues("core.file.storage=s3", "core.file.s3.bucket=my-bucket")
			  .run(context -> {
				  StartupReport report = context.getBean(FileStartupReport.class).report();
				  assertThat(report.isReady()).isFalse();
				  assertThat(report.getDetail()).contains("software.amazon.awssdk:s3");
			  });
	}
}
