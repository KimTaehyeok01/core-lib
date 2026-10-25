package com.corelib.auth.nice;

import com.corelib.webclient.WebClientUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 인증 헤더는 호출마다 다르다(token=Basic, url·result=Bearer) — 기본 헤더 없이 만든다 */
@AutoConfiguration
@EnableConfigurationProperties(NiceAuthProperty.class)
// auth.nice.client-id 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
// client-id 가 있는데 나머지 설정이 잘못됐으면 기동을 멈춘다 — 첫 호출의 500 이 아니라 배포 시점에 드러나게
@ConditionalOnExpression("!'${auth.nice.client-id:}'.isBlank()")
public class NiceAuthInitialize {

	public NiceAuthInitialize(NiceAuthProperty property) {
		// 메시지에는 키 이름만 담긴다 — 설정값(시크릿·URL)은 기동 로그에 남지 않는다.
		property.validate();
		NiceAuthUtils.initialize(
			WebClientUtils.builder(property.resolvedBaseUrl(), property.getConnectTimeout(), property.getReadTimeout()).build(),
			property, property.callTimeout());
	}
}
