package com.corelib.jwt;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 기동 안내 — 알고리즘·발급 가능 여부·유효시간·Bearer 필터 연동. 키는 찍지 않는다 */
@AutoConfiguration
public class JwtStartupReport implements StartupReporter {

	private static final String NAME = "JwtUtils";

	private final ObjectProvider<JwtInitialize> initialize;
	private final ObjectProvider<JwtProperty> property;
	private final ListableBeanFactory beanFactory;

	public JwtStartupReport(ObjectProvider<JwtInitialize> initialize, ObjectProvider<JwtProperty> property, ListableBeanFactory beanFactory) {
		this.initialize = initialize;
		this.property = property;
		this.beanFactory = beanFactory;
	}

	@Override
	public StartupReport report() {
		JwtProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || value == null) {
			return StartupReport.notReady(NAME, "core.jwt.private-key·public-key·secret 미설정 — 호출 시 '미초기화' 오류");
		}
		String mode = JwtUtils.isIssuable() ? "발급+검증" : "검증 전용";
		String filter = beanFactory.containsBean(JwtSecurityInitialize.CUSTOMIZER_BEAN) ? "Bearer 필터 연동" : "Bearer 필터 없음";
		return StartupReport.ready(NAME, value.getAlgorithm() + " " + mode + ", kid=" + value.getKeyId() + ", iss=" + value.getIssuer()
			+ ", access " + value.getAccessTokenTtl().toMinutes() + "분 / refresh " + value.getRefreshTokenTtl().toDays() + "일, " + filter);
	}
}
