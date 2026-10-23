package com.corelib.excel;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** core.excel.max-rows(기본 10,000)를 ExcelUtils·CsvUtils 에 넣는다 — 키가 필요 없어 항상 켜진다 */
@AutoConfiguration
public class ExcelInitialize {

	private final int maxRows;

	public ExcelInitialize(@Value("${core.excel.max-rows:10000}") int maxRows) {
		if (maxRows < 1) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.excel.max-rows 는 1 이상이어야 합니다.");
		}
		this.maxRows = maxRows;
		ExcelUtils.initialize(maxRows);
	}

	@Bean
	public StartupReporter coreExcelStartupReport() {
		return () -> StartupReport.ready("ExcelUtils", "읽기 최대 " + maxRows + "행, xlsx·csv");
	}
}
