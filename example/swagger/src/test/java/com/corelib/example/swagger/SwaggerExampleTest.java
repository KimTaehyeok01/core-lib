// swagger 예제를 실제 앱으로 띄워 OpenAPI 문서(제목·보안 스킴·자물쇠 API) 검증
package com.corelib.example.swagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class SwaggerExampleTest {

	private final MockMvc mockMvc;
	private final List<StartupReporter> startupReporters;

	@Test
	@DisplayName("OpenAPI 문서 - 설정 제목, bearerAuth 스킴, 등록 API 에만 보안 요구")
	void apiDocs() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			   .andExpect(status().isOk())
			   .andExpect(jsonPath("$.info.title").value("core-lib 예제 API"))
			   .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
			   .andExpect(jsonPath("$.paths['/api/products/create'].post.security[0].bearerAuth").exists())
			   .andExpect(jsonPath("$.paths['/api/products/list'].get.security").doesNotExist());
	}

	@Test
	@DisplayName("공통 에러 응답 - 공개 API 는 400·404·500, 인증 API 는 401·403 추가, 직접 적은 400 설명 유지")
	void commonErrorResponses() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			   .andExpect(jsonPath("$.paths['/api/products/list'].get.responses['404'].content['application/json'].example.code").value("C404"))
			   .andExpect(jsonPath("$.paths['/api/products/list'].get.responses['500']").exists())
			   .andExpect(jsonPath("$.paths['/api/products/list'].get.responses['401']").doesNotExist())
			   .andExpect(jsonPath("$.paths['/api/products/create'].post.responses['401']").exists())
			   .andExpect(jsonPath("$.paths['/api/products/create'].post.responses['403']").exists())
			   .andExpect(jsonPath("$.paths['/api/products/create'].post.responses['400'].description").value("요청 검증 실패 (C400)"))
			   .andExpect(jsonPath("$.components.schemas.CoreErrorResponse.properties.code").exists());
	}

	@Test
	@DisplayName("Swagger UI 기본값 - API 목록 접힘, Schemas 숨김")
	void uiDefaults() throws Exception {
		mockMvc.perform(get("/v3/api-docs/swagger-config"))
			   .andExpect(jsonPath("$.docExpansion").value("none"))
			   .andExpect(jsonPath("$.defaultModelsExpandDepth").value(-1));
	}

	@Test
	@DisplayName("Swagger UI 페이지 제공")
	void swaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("기동 안내 - Swagger 보고")
	void startupReport() {
		StartupReport report = startupReporters.stream().map(StartupReporter::report)
											   .filter(item -> item.getName().equals("Swagger")).findFirst().orElseThrow();
		assertThat(report.getPath()).isEqualTo("/swagger-ui.html");
	}
}
