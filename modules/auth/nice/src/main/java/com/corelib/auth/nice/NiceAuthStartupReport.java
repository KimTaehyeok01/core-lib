// 기동 안내에 NiceAuthUtils 초기화 결과(인증수단·return_url·접속 주소)를 알리는 자동설정 — 키는 찍지 않는다
package com.corelib.auth.nice;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 키가 없어 {@link NiceAuthInitialize} 가 꺼져도 "미초기화" 를 알리도록 조건 없이 등록한다 */
@AutoConfiguration
public class NiceAuthStartupReport implements StartupReporter {

	private static final String NAME = "NiceAuthUtils";

	private final ObjectProvider<NiceAuthInitialize> initialize;
	private final ObjectProvider<NiceAuthProperty> property;

	public NiceAuthStartupReport(ObjectProvider<NiceAuthInitialize> initialize, ObjectProvider<NiceAuthProperty> property) {
		this.initialize = initialize;
		this.property = property;
	}

	@Override
	public StartupReport report() {
		NiceAuthProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || value == null) {
			return StartupReport.notReady(NAME, "auth.nice.client-id 미설정 — 호출 시 '미초기화' 오류");
		}
		return StartupReport.ready(NAME, "인증수단=" + value.getSvcTypes() + ", return-url=" + value.getReturnUrl()
			+ ", base-url=" + value.resolvedBaseUrl());
	}
}
