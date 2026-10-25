package com.corelib.payment.nice;

import com.corelib.webclient.WebClientUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties(NicePayProperty.class)
// payment.nice.client-key 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
@ConditionalOnExpression("!'${payment.nice.client-key:}'.isBlank()")
public class NicePayInitialize {

	public NicePayInitialize(NicePayProperty property) {
		property.validate();

		// NICEPAY Basic 인증 = base64(clientKey + ":" + secretKey)
		WebClient client = WebClientUtils.builder(property.resolvedBaseUrl(), property.getConnectTimeout(), property.getReadTimeout())
										 .defaultHeaders(headers -> headers.setBasicAuth(property.getClientKey(), property.getSecretKey()))
										 .build();
		NicePayUtils.initialize(client, property, property.callTimeout());
	}
}
