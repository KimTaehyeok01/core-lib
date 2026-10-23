package com.corelib.payment.toss;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 키가 없어 {@link TossPayInitialize} 가 꺼져도 "미초기화" 를 알리도록 조건 없이 등록한다 */
@AutoConfiguration
public class TossPayStartupReport implements StartupReporter {

	private static final String NAME = "TossPayUtils";

	private final ObjectProvider<TossPayInitialize> initialize;
	private final ObjectProvider<TossPayProperty> property;

	public TossPayStartupReport(ObjectProvider<TossPayInitialize> initialize, ObjectProvider<TossPayProperty> property) {
		this.initialize = initialize;
		this.property = property;
	}

	@Override
	public StartupReport report() {
		TossPayProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || value == null) {
			return StartupReport.notReady(NAME, "payment.toss.secret-key 미설정 — 호출 시 '미초기화' 오류");
		}
		String keyType = value.getSecretKey().startsWith("test_") ? "테스트 키" : "운영 키";
		return StartupReport.ready(NAME, keyType + ", base-url=" + value.resolvedBaseUrl() + ", "
			+ StartupReport.timeout(value.getConnectTimeout(), value.getReadTimeout()));
	}
}
