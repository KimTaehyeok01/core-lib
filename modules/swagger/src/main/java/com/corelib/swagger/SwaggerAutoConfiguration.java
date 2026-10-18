// core.swagger 설정으로 기본 OpenAPI 문서(정보 + JWT 보안 스킴)를 등록하는 자동설정
package com.corelib.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@EnableConfigurationProperties(SwaggerProperty.class)
public class SwaggerAutoConfiguration {

	/** 컨트롤러에서 @SecurityRequirement(name = BEARER_SCHEME) 로 참조하는 보안 스킴 이름 */
	public static final String BEARER_SCHEME = "bearerAuth";

	/** 소비 앱이 OpenAPI 빈을 직접 정의하면 그쪽을 우선 */
	@Bean
	@ConditionalOnMissingBean
	public OpenAPI coreOpenApi(SwaggerProperty property) {
		return new OpenAPI()
			.info(new Info()
				.title(property.getTitle())
				.description(property.getDescription())
				.version(property.getVersion()))
			.components(new Components()
				.addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")));
	}
}
