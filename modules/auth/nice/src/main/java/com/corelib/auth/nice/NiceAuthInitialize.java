// auth.nice.client-id 가 있으면 timeout 이 걸린 WebClient 로 NiceAuthUtils 를 초기화하는 자동설정
package com.corelib.auth.nice;

import com.corelib.webclient.WebClientUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 인증 헤더는 호출마다 다르다(token=Basic, url·result=Bearer) — 기본 헤더 없이 만든다 */
@AutoConfiguration
@EnableConfigurationProperties(NiceAuthProperty.class)
// auth.nice.client-id 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
@ConditionalOnExpression("!'${auth.nice.client-id:}'.isBlank()")
public class NiceAuthInitialize {

	public NiceAuthInitialize(NiceAuthProperty property) {
		NiceAuthUtils.initialize(
			WebClientUtils.builder(property.resolvedBaseUrl(), property.getConnectTimeout(), property.getReadTimeout()).build(),
			property);
	}
}
