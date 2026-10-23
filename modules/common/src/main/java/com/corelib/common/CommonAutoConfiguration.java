package com.corelib.common;

import com.corelib.common.exception.CoreExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonAutoConfiguration {

	/** 소비 앱 패키지가 com.corelib 밖이어도 컴포넌트 스캔 없이 등록. 직접 정의하면 그쪽 우선 */
	@Bean
	@ConditionalOnMissingBean
	public CoreExceptionHandler coreExceptionHandler() {
		return new CoreExceptionHandler();
	}
}
