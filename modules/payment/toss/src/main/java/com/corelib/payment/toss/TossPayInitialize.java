// payment.toss.secret-key 가 있으면 Basic 인증·timeout 이 걸린 WebClient 로 TossPayUtils 를 초기화하는 자동설정
package com.corelib.payment.toss;

import com.corelib.webclient.WebClientUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties(TossPayProperty.class)
// payment.toss.secret-key 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
@ConditionalOnExpression("!'${payment.toss.secret-key:}'.isBlank()")
public class TossPayInitialize {

	public TossPayInitialize(TossPayProperty property) {
		property.validate();

		// 토스 Basic 인증 = base64(secretKey + ":") — 비밀번호 자리는 빈 문자열
		WebClient client = WebClientUtils.builder(property.resolvedBaseUrl(), property.getConnectTimeout(), property.getReadTimeout())
										 .defaultHeaders(headers -> headers.setBasicAuth(property.getSecretKey(), ""))
										 .build();
		TossPayUtils.initialize(client);
	}
}
