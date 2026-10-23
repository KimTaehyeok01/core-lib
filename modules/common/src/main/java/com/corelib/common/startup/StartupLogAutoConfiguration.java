package com.corelib.common.startup;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "core.startup-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StartupLogAutoConfiguration {

	/** 모듈 보고는 등록 순서(자동설정 순서)대로 찍는다 */
	@Bean
	public StartupLogPrinter coreStartupLogPrinter(ObjectProvider<StartupReporter> reporters) {
		return new StartupLogPrinter(reporters.orderedStream().toList());
	}
}
