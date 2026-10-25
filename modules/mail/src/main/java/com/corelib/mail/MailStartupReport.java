package com.corelib.mail;

import com.corelib.lang.MaskingUtils;
import com.corelib.lang.StringUtils;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.env.Environment;

/** spring.mail.host 가 없어 {@link MailInitialize} 가 꺼져도 "미초기화" 를 알리도록 조건 없이 등록한다 */
@AutoConfiguration
public class MailStartupReport implements StartupReporter {

	private static final String NAME = "MailUtils";

	private final ObjectProvider<MailInitialize> initialize;
	private final ObjectProvider<MailProperty> property;
	private final Environment environment;

	public MailStartupReport(ObjectProvider<MailInitialize> initialize, ObjectProvider<MailProperty> property, Environment environment) {
		this.initialize = initialize;
		this.property = property;
		this.environment = environment;
	}

	@Override
	public StartupReport report() {
		MailInitialize current = initialize.getIfAvailable();
		if (current == null) {
			return StartupReport.notReady(NAME, "spring.mail.host 미설정 — 호출 시 '미초기화' 오류");
		}
		MailProperty value = property.getIfAvailable();
		String from = value == null || StringUtils.isBlank(value.getFrom()) ? "미설정(core.mail.from)" : MaskingUtils.email(value.getFrom());
		return StartupReport.ready(NAME, "smtp=" + environment.getProperty("spring.mail.host") + ":"
			+ StringUtils.defaultIfBlank(environment.getProperty("spring.mail.port"), "25") + ", from=" + from + ", " + current.timeout());
	}
}
