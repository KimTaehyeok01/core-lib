// 기동 안내에 KcbAuthUtils 초기화 결과(대상 서버·OkCert jar 유무)를 알리는 자동설정 — 회원사코드·라이선스 경로는 찍지 않는다
package com.corelib.auth.kcb;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 회원사코드가 없어 {@link KcbAuthInitialize} 가 꺼져도 "미초기화" 를 알리도록 조건 없이 등록한다 */
@AutoConfiguration
public class KcbAuthStartupReport implements StartupReporter {

	private static final String NAME = "KcbAuthUtils";

	private final ObjectProvider<KcbAuthInitialize> initialize;
	private final ObjectProvider<KcbAuthProperty> property;

	public KcbAuthStartupReport(ObjectProvider<KcbAuthInitialize> initialize, ObjectProvider<KcbAuthProperty> property) {
		this.initialize = initialize;
		this.property = property;
	}

	@Override
	public StartupReport report() {
		KcbAuthProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || value == null) {
			return StartupReport.notReady(NAME, "auth.kcb.cp-cd 미설정 — 호출 시 '미초기화' 오류");
		}
		// KCB jar 는 계약 후 받는 파일이라 없을 수 있다 — 기동 시점에 미리 알려준다
		String jar = isOkCertPresent() ? "OkCert jar 있음" : "OkCert jar 없음(호출 시 MODULE_NOT_FOUND)";
		return StartupReport.ready(NAME, "target=" + value.getTarget() + ", " + jar + ", popup-url=" + value.getPopupUrl());
	}

	private static boolean isOkCertPresent() {
		try {
			Class.forName("kcb.module.v3.OkCert", false, KcbAuthStartupReport.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}
