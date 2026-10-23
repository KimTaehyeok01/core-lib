package com.corelib.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

	/** core.swagger.error-responses.enabled=false 면 공통 에러 응답을 붙이지 않는다 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(OperationCustomizer.class)
	@ConditionalOnProperty(prefix = "core.swagger.error-responses", name = "enabled", havingValue = "true", matchIfMissing = true)
	static class ErrorResponseConfiguration {

		@Bean
		OperationCustomizer coreErrorResponseCustomizer(SwaggerProperty property) {
			return new SwaggerErrorResponseCustomizer(property.getErrorResponses());
		}

		/** 공통 에러 본문 스키마 — 소비 앱이 OpenAPI 빈을 직접 만들어도 붙도록 customizer 로 넣는다 */
		@Bean
		OpenApiCustomizer coreErrorSchemaCustomizer() {
			return openApi -> {
				if (openApi.getComponents() == null) {
					openApi.setComponents(new Components());
				}
				Schema<?> error = new ObjectSchema()
					.description("공통 에러 응답 (ApiResponse)")
					.addProperty("success", new BooleanSchema().example(false))
					.addProperty("data", new ObjectSchema().nullable(true).example(null))
					.addProperty("code", new StringSchema().description("에러코드 — C400·C404 또는 벤더 코드(TOSS_xxx 등)"))
					.addProperty("message", new StringSchema().description("사용자에게 보여줄 메시지"));
				openApi.getComponents().addSchemas(SwaggerErrorResponseCustomizer.ERROR_SCHEMA, error);
			};
		}
	}
}
