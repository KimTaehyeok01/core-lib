package com.corelib.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ExcelInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ExcelInitialize.class));

	@AfterEach
	void reset() {
		ExcelUtils.initialize(10_000);
	}

	@Test
	@DisplayName("설정 없음 — 기본 10,000행, ZipSecureFile 압축 비율 1%·항목 100MB 상한 명시, 기동 안내 표시")
	void defaults() {
		ExcelUtils.initialize(1);

		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(ExcelUtils.maxRows()).isEqualTo(10_000);
			assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(0.01);
			assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(100L * 1024 * 1024);
			StartupReport report = context.getBean(StartupReporter.class).report();
			assertThat(report.isReady()).isTrue();
			assertThat(report.getDetail()).contains("10000행", "1%", "100MB");
		});
	}

	@Test
	@DisplayName("max-rows 설정 — ExcelUtils·CsvUtils 상한과 빈 행 포함 행 번호 상한(×2+100)에 반영")
	void configured() {
		runner.withPropertyValues("core.excel.max-rows=5")
			  .run(context -> {
				  assertThat(context).hasNotFailed();
				  assertThat(ExcelUtils.maxRows()).isEqualTo(5);
				  assertThat(ExcelUtils.maxScanRows()).isEqualTo(110);
				  assertThat(context.getBean(StartupReporter.class).report().getDetail()).contains("5행");
			  });
	}

	@Test
	@DisplayName("max-rows 가 1 미만·숫자 아님이면 기동 실패")
	void invalid() {
		runner.withPropertyValues("core.excel.max-rows=0")
			  .run(context -> assertThat(context).hasFailed()
												 .getFailure().rootCause().hasMessageContaining("core.excel.max-rows"));
		runner.withPropertyValues("core.excel.max-rows=many")
			  .run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("행 번호 상한은 int 를 넘지 않는다")
	void scanLimitDoesNotOverflow() {
		ExcelUtils.initialize(Integer.MAX_VALUE);

		assertThat(ExcelUtils.maxScanRows()).isEqualTo(Integer.MAX_VALUE);
	}
}
