// swagger 예제를 실제 앱으로 띄워 OpenAPI 문서(제목·보안 스킴·자물쇠 API) 검증
package com.corelib.example.swagger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
	@DisplayName("Swagger UI 페이지 제공")
	void swaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}
}
