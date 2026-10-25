package com.corelib.excel;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * core.excel.max-rows(기본 10,000)를 ExcelUtils·CsvUtils 에 넣는다 — 키가 필요 없어 항상 켜진다.
 * xlsx 압축 폭탄 상한(ZipSecureFile)도 여기서 명시한다 — POI 전역 설정이라 앱 안의 다른 POI 사용에도 적용된다.
 */
@AutoConfiguration
public class ExcelInitialize {

	/** 풀린 크기 대비 압축 크기 하한 1% — 100배 넘게 부풀어 오르는 zip 항목은 거부 (POI 기본값을 명시) */
	static final double MIN_INFLATE_RATIO = 0.01;
	/** zip 항목 하나의 풀린 크기 상한 100MB (POI 기본 4GB) */
	static final long MAX_ENTRY_SIZE = 100L * 1024 * 1024;

	private final int maxRows;

	public ExcelInitialize(@Value("${core.excel.max-rows:10000}") int maxRows) {
		if (maxRows < 1) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.excel.max-rows 는 1 이상이어야 합니다.");
		}
		this.maxRows = maxRows;
		ExcelUtils.initialize(maxRows);
		ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
		ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
	}

	@Bean
	public StartupReporter coreExcelStartupReport() {
		return () -> StartupReport.ready("ExcelUtils", "읽기 최대 " + maxRows + "행, xlsx·csv, 압축 해제 비율 1%·항목 100MB 상한");
	}
}
