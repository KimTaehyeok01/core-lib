package com.corelib.swagger;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SwaggerAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SwaggerAutoConfiguration.class));

	@Test
	@DisplayName("설정값으로 OpenAPI 정보와 JWT 보안 스킴 등록")
	void registersOpenApiFromProperties() {
		runner.withPropertyValues("core.swagger.title=주문 API", "core.swagger.version=v2")
			  .run(context -> {
				  OpenAPI openApi = context.getBean(OpenAPI.class);
				  assertThat(openApi.getInfo().getTitle()).isEqualTo("주문 API");
				  assertThat(openApi.getInfo().getVersion()).isEqualTo("v2");
				  assertThat(openApi.getComponents().getSecuritySchemes())
					  .containsKey(SwaggerAutoConfiguration.BEARER_SCHEME);
			  });
	}

	@Test
	@DisplayName("소비 앱이 OpenAPI 빈을 정의하면 기본 빈은 등록하지 않음")
	void backsOffWhenUserDefinesOpenApi() {
		runner.withBean(OpenAPI.class, () -> new OpenAPI().info(new Info().title("custom")))
			  .run(context -> {
				  assertThat(context).hasSingleBean(OpenAPI.class);
				  assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("custom");
			  });
	}
}
