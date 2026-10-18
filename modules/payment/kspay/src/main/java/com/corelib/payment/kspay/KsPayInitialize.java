// payment.kspay.mid 가 있으면 승인(통합모듈)·취소(webfep) WebClient 로 KsPayUtils 를 초기화하는 자동설정
package com.corelib.payment.kspay;

import com.corelib.lang.StringUtils;
import com.corelib.webclient.WebClientUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties(KsPayProperty.class)
// payment.kspay.mid 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
@ConditionalOnExpression("!'${payment.kspay.mid:}'.isBlank()")
public class KsPayInitialize {

	public KsPayInitialize(KsPayProperty property) {
		property.validate();

		WebClient approvalClient = WebClientUtils.builder(property.getApprovalBaseUrl(), property.getConnectTimeout(),
			property.getReadTimeout()).build();
		// webfep 인증 = "pgapi " + KSTA 의 pgapi 키 — 키가 없으면 헤더 없이 만들고 cancel() 이 설정 오류로 알린다
		WebClient webfepClient = WebClientUtils.builder(property.getWebfepBaseUrl(), property.getConnectTimeout(),
				property.getReadTimeout())
			.defaultHeaders(headers -> {
				if (StringUtils.isNotBlank(property.getApiKey())) {
					headers.set(HttpHeaders.AUTHORIZATION, "pgapi " + property.getApiKey());
				}
			})
			.build();
		KsPayUtils.initialize(property, approvalClient, webfepClient);
	}
}
