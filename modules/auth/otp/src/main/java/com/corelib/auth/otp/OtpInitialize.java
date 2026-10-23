package com.corelib.auth.otp;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** auth.otp.* 를 OtpUtils 에 넣는다 — 키가 필요 없어 항상 켜진다 */
@AutoConfiguration
@EnableConfigurationProperties(OtpProperty.class)
public class OtpInitialize {

	public OtpInitialize(OtpProperty property) {
		OtpUtils.initialize(property);
	}

	@Bean
	public StartupReporter coreOtpStartupReport(OtpProperty property) {
		return () -> StartupReport.ready("OtpUtils", "issuer=" + property.getIssuer() + ", " + property.getAlgorithm() + " "
			+ property.getDigits() + "자리 " + property.getPeriod().toSeconds() + "초, 허용 ±" + property.getWindow());
	}
}
