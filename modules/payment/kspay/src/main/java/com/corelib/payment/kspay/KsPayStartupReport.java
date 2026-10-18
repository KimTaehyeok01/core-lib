// 기동 안내에 KsPayUtils 초기화 결과(상점아이디·승인/취소 주소·취소 키 유무)를 알리는 자동설정 — 키는 찍지 않는다
package com.corelib.payment.kspay;

import com.corelib.lang.StringUtils;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** mid 가 없어 {@link KsPayInitialize} 가 꺼져도 "미초기화" 를 알리도록 조건 없이 등록한다 */
@AutoConfiguration
public class KsPayStartupReport implements StartupReporter {

	private static final String NAME = "KsPayUtils";

	private final ObjectProvider<KsPayInitialize> initialize;
	private final ObjectProvider<KsPayProperty> property;

	public KsPayStartupReport(ObjectProvider<KsPayInitialize> initialize, ObjectProvider<KsPayProperty> property) {
		this.initialize = initialize;
		this.property = property;
	}

	@Override
	public StartupReport report() {
		KsPayProperty value = property.getIfAvailable();
		if (initialize.getIfAvailable() == null || value == null) {
			return StartupReport.notReady(NAME, "payment.kspay.mid 미설정 — 호출 시 '미초기화' 오류");
		}
		// 상점아이디는 결제창 form(sndStoreid)으로 브라우저에 나가는 공개값이라 그대로 찍는다
		String cancel = StringUtils.isBlank(value.getApiKey()) ? "취소 불가(payment.kspay.api-key 미설정)" : "취소 가능";
		return StartupReport.ready(NAME, "mid=" + value.getMid() + ", 승인=" + value.getApprovalBaseUrl()
			+ ", 취소=" + value.getWebfepBaseUrl() + " " + cancel);
	}
}
