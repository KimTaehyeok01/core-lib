package com.corelib.payment.nice;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 키가 없어 {@link NicePayInitialize} 가 꺼져도 "미초기화" 를 알리도록 조건 없이 등록한다 */
@AutoConfiguration
public class NicePayStartupReport implements StartupReporter {

	private static final String NAME = "NicePayUtils";

	private final ObjectProvider<NicePayInitialize> initialize;
	private final ObjectProvider<NicePayProperty> property;

	public NicePayStartupReport(ObjectProvider<NicePayInitialize> initialize, ObjectProvider<NicePayProperty> property) {
		this.initialize = initialize;
		this.property = property;
	}

	@Override
	public StartupReport report() {
		NicePayProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || value == null) {
			return StartupReport.notReady(NAME, "payment.nice.client-key 미설정 — 호출 시 '미초기화' 오류");
		}
		String baseUrl = value.resolvedBaseUrl();
		return StartupReport.ready(NAME, (baseUrl.contains("sandbox") ? "샌드박스" : "운영") + ", base-url=" + baseUrl + ", "
			+ StartupReport.timeout(value.getConnectTimeout(), value.getReadTimeout()));
	}
}
