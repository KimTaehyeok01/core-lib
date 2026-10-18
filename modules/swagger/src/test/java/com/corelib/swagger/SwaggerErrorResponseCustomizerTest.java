// 공통 에러 응답 — 공개 API 는 codes 만, 인증 API 는 secured-codes 까지, 직접 적은 응답은 유지하는지 검증
package com.corelib.swagger;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

class SwaggerErrorResponseCustomizerTest {

	/** 문서화 대상 흉내 — 공개 메서드 하나, 인증 메서드 하나 */
	static class SampleController {

		public String open() {
			return "open";
		}

		@SecurityRequirement(name = SwaggerAutoConfiguration.BEARER_SCHEME)
		public String secured() {
			return "secured";
		}
	}

	private static HandlerMethod handler(String method) throws NoSuchMethodException {
		return new HandlerMethod(new SampleController(), SampleController.class.getMethod(method));
	}

	private static Operation withOk() {
		return new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
	}

	@Test
	@DisplayName("공개 API - 기본 400·404·500 만, 401·403 없음, 예시는 전역 핸들러와 같은 코드")
	void openApi() throws Exception {
		Operation operation = new SwaggerErrorResponseCustomizer(new SwaggerProperty.ErrorResponses()).customize(withOk(), handler("open"));

		assertThat(operation.getResponses()).containsOnlyKeys("200", "400", "404", "500");
		Map<?, ?> example = (Map<?, ?>) operation.getResponses().get("404").getContent().get("application/json").getExample();
		assertThat(example.get("code")).isEqualTo("C404");
		assertThat(example.get("success")).isEqualTo(false);
	}

	@Test
	@DisplayName("인증 API - 401·403 추가, 직접 적은 400 설명은 덮어쓰지 않음")
	void securedApi() throws Exception {
		Operation operation = withOk();
		operation.getResponses().addApiResponse("400", new ApiResponse().description("요청 검증 실패 (직접 작성)"));

		new SwaggerErrorResponseCustomizer(new SwaggerProperty.ErrorResponses()).customize(operation, handler("secured"));

		assertThat(operation.getResponses()).containsOnlyKeys("200", "400", "401", "403", "404", "500");
		assertThat(operation.getResponses().get("400").getDescription()).isEqualTo("요청 검증 실패 (직접 작성)");
	}

	@Test
	@DisplayName("codes 를 바꾸면 그 코드만 — 공통 목록에 없는 코드(409)는 C409")
	void customCodes() throws Exception {
		SwaggerProperty.ErrorResponses property = new SwaggerProperty.ErrorResponses();
		property.setCodes(List.of(409, 500));
		property.setSecuredCodes(List.of());

		Operation operation = new SwaggerErrorResponseCustomizer(property).customize(withOk(), handler("secured"));

		assertThat(operation.getResponses()).containsOnlyKeys("200", "409", "500");
		assertThat(operation.getResponses().get("409").getDescription()).startsWith("C409");
	}

	@Test
	@DisplayName("core.swagger.error-responses.enabled=false 면 커스터마이저를 등록하지 않음")
	void toggle() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SwaggerAutoConfiguration.class));

		runner.run(context -> assertThat(context).hasSingleBean(OperationCustomizer.class));
		runner.withPropertyValues("core.swagger.error-responses.enabled=false")
			  .run(context -> assertThat(context).doesNotHaveBean(OperationCustomizer.class));
	}
}
